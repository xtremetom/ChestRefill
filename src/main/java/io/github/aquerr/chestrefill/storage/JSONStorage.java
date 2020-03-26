package io.github.aquerr.chestrefill.storage;

import com.flowpowered.math.vector.Vector3i;
import com.google.common.reflect.TypeToken;
import io.github.aquerr.chestrefill.ChestRefill;
import io.github.aquerr.chestrefill.PluginInfo;
import io.github.aquerr.chestrefill.entities.ContainerLocation;
import io.github.aquerr.chestrefill.entities.Kit;
import io.github.aquerr.chestrefill.entities.RefillableContainer;
import io.github.aquerr.chestrefill.entities.RefillableItem;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.gson.GsonConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Created by Aquerr on 2018-02-12.
 */
public class JSONStorage implements Storage
{
    private Path containersPath;
    private GsonConfigurationLoader containersLoader;
    private ConfigurationNode containersNode;

    private Path kitsPath;
    private GsonConfigurationLoader kitsLoader;
    private ConfigurationNode kitsNode;

    private WatchService watchService;
    private WatchKey key;

    private static final TypeToken<Kit> KIT_TYPE_TOKEN = TypeToken.of(Kit.class);
//    private static final TypeToken<RefillableItem> REFILLABLE_ITEM_TYPE_TOKEN = TypeToken.of(RefillableItem.class);
    private static final TypeToken<List<Kit>> KIT_LIST_TYPE_TOKEN = new TypeToken<List<Kit>>(){};
    private static final TypeToken<List<RefillableItem>> REFILLABLE_TIME_LIST_TYPE_TOKEN = new TypeToken<List<RefillableItem>>(){};

    public JSONStorage(Path configDir)
    {
        try
        {
            containersPath = Paths.get(configDir + "/containers.json");
            kitsPath = Paths.get(configDir + "/kits.json");

            if (!Files.exists(containersPath))
            {
                Files.createFile(containersPath);
            }

            if (!Files.exists(kitsPath))
            {
                Files.createFile(kitsPath);
            }


            containersLoader = GsonConfigurationLoader.builder().setPath(containersPath).build();
            containersNode = containersLoader.load();

            kitsLoader = GsonConfigurationLoader.builder().setPath(kitsPath).build();
            kitsNode = kitsLoader.load();

            //Register watcher
            watchService = configDir.getFileSystem().newWatchService();
            key = configDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

            Task.Builder changeTask = Sponge.getScheduler().createTaskBuilder();
            //Run a checkFileUpdate task every 2,5 second
            changeTask.async().intervalTicks(50L).execute(checkFileUpdate()).submit(ChestRefill.getInstance());

        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public boolean addOrUpdateContainer(RefillableContainer refillableContainer)
    {
        try
        {
            //We are using block position and recreating location on retrieval.
            String blockPositionAndWorldUUID = refillableContainer.getContainerLocation().getBlockPosition().toString() + "|" + refillableContainer.getContainerLocation().getWorldUUID();

            List<RefillableItem> items = new ArrayList<>(refillableContainer.getItems());

            //Set container's name
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "name").setValue(refillableContainer.getName());

            //Set container's block type
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "container-block-type").setValue(TypeToken.of(BlockType.class), refillableContainer.getContainerBlockType());

            //Set container's kit
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "kit").setValue(refillableContainer.getKitName());

            if(refillableContainer.getKitName().equals(""))
            {
                //Set container's items
                containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "items").setValue(REFILLABLE_TIME_LIST_TYPE_TOKEN, items);
            }
            else
            {
                containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "items").setValue(new ArrayList<>());
            }

            //Set container's regeneration time (in seconds)
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "time").setValue(refillableContainer.getRestoreTime());

            //Set container's "one itemstack at time"
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "one-item-at-time").setValue(refillableContainer.isOneItemAtTime());

            //Set container's should-replace-existing-items property
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "replace-existing-items").setValue(refillableContainer.shouldReplaceExistingItems());

            //Set container's should-be-hidden-if-no-items
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "hidden-if-no-items").setValue(refillableContainer.shouldBeHiddenIfNoItems());

            //Set container's hidding block
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "hiding-block").setValue(TypeToken.of(BlockType.class), refillableContainer.getHidingBlock());

            //Set required permission
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "required-permission").setValue(refillableContainer.getRequiredPermission());

            containersLoader.save(containersNode);

            return true;
        }
        catch (Exception exception)
        {
            exception.printStackTrace();
            Sponge.getServer().getConsole().sendMessage(PluginInfo.ERROR_PREFIX.concat(Text.of("Could not add/update container in the storage. Container = " + refillableContainer)));
        }

        return false;
    }

    public boolean removeRefillableContainer(ContainerLocation containerLocation)
    {
        try
        {
            //We are using block position and recreating location on retrieval.
            String blockPositionAndWorldUUID = containerLocation.getBlockPosition().toString() + "|" + containerLocation.getWorldUUID();

            containersNode.getNode("chestrefill", "refillable-containers").removeChild(blockPositionAndWorldUUID);

            containersLoader.save(containersNode);

            return true;
        }
        catch (IOException exception)
        {
            exception.printStackTrace();
            Sponge.getServer().getConsole().sendMessage(PluginInfo.ERROR_PREFIX.concat(Text.of("Could not remove container from the storage. Container location = " + containerLocation)));
        }

        return false;
    }

    @Override
    public List<ContainerLocation> getContainerLocations()
    {
        Set<Object> objectList = containersNode.getNode("chestrefill", "refillable-containers").getChildrenMap().keySet();
        List<ContainerLocation> containerLocations = new ArrayList<>();

        for (Object object : objectList)
        {
            String chestPositionAndWorldUUIDString = (String) object;
            String splitter = "\\|";

            String[] chestPosAndWorldUUID = chestPositionAndWorldUUIDString.split(splitter);
            UUID worldUUID = UUID.fromString(chestPosAndWorldUUID[1]);

            String[] vectors = chestPosAndWorldUUID[0].replace("(", "").replace(")", "").replace(" ", "").split(",");
            int x = Integer.parseInt(vectors[0]);
            int y = Integer.parseInt(vectors[1]);
            int z = Integer.parseInt(vectors[2]);

            ContainerLocation containerLocation = new ContainerLocation(Vector3i.from(x, y, z), worldUUID);
            containerLocations.add(containerLocation);
        }

        return containerLocations;
    }

    @Override
    public List<RefillableContainer> getRefillableContainers()
    {
        List<RefillableContainer> refillingContainersList = new ArrayList<>();

        for (ContainerLocation containerLocation : getContainerLocations())
        {
            RefillableContainer refillableContainer = getRefillableContainerFromFile(containerLocation);
            if (refillableContainer != null)
                refillingContainersList.add(refillableContainer);
        }

        return refillingContainersList;
    }

    @Override
    public boolean updateContainerTime(ContainerLocation containerLocation, int time)
    {
        try
        {
            //We are using block position and recreating location on retrieval.
            String blockPositionAndWorldUUID = containerLocation.getBlockPosition().toString() + "|" + containerLocation.getWorldUUID();

            //Set chest's regeneration time (in seconds)
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "time").setValue(time);

            containersLoader.save(containersNode);

            return true;
        }
        catch (IOException exception)
        {
            exception.printStackTrace();
            Sponge.getServer().getConsole().sendMessage(PluginInfo.ERROR_PREFIX.concat(Text.of("Could not update container restore time. Container location = " + containerLocation + " | New time = " + time)));
        }

        return false;
    }

    @Override
    public boolean changeContainerName(ContainerLocation containerLocation, String containerName)
    {
        try
        {
            //We are using block position and recreating location on retrieval.
            String blockPositionAndWorldUUID = containerLocation.getBlockPosition().toString() + "|" + containerLocation.getWorldUUID();

            //Set chest's name
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "name").setValue(containerName);
            containersLoader.save(containersNode);

            return true;
        }
        catch (IOException exception)
        {
            exception.printStackTrace();
            Sponge.getServer().getConsole().sendMessage(PluginInfo.ERROR_PREFIX.concat(Text.of("Could not change container name. Container location = " + containerLocation + " | Container name = " + containerName)));
        }

        return false;
    }

    @Override
    public List<Kit> getKits()
    {
        try
        {
            final List<Kit> kits = kitsNode.getNode("kits").getList(KIT_TYPE_TOKEN, new ArrayList<>());
            return kits;
        }
        catch(ObjectMappingException e)
        {
            e.printStackTrace();
            Sponge.getServer().getConsole().sendMessage(PluginInfo.ERROR_PREFIX.concat(Text.of("Could not get kits from the storage.")));
        }

        return new ArrayList<>();
    }

    @Override
    public boolean createKit(Kit kit)
    {
        try
        {
            final ConfigurationNode configurationNode = kitsNode.getNode("kits").getAppendedNode();
            configurationNode.setValue(KIT_TYPE_TOKEN, kit);
            kitsLoader.save(kitsNode);
            return true;
        }
        catch(Exception e)
        {
            e.printStackTrace();
            Sponge.getServer().getConsole().sendMessage(PluginInfo.ERROR_PREFIX.concat(Text.of("Could not add kit to the storage. Kit = " + kit)));
        }

        return false;
    }

    @Override
    public boolean removeKit(String kitName)
    {
        try
        {
            List<Kit> kits = new ArrayList<>(kitsNode.getNode("kits").getList(KIT_TYPE_TOKEN));
            kits.removeIf(x->x.getName().equals(kitName));
            kitsNode.getNode("kits").setValue(KIT_LIST_TYPE_TOKEN, kits);
            kitsLoader.save(kitsNode);

            //Remove the kit from containers
            final Set<Object> blockPositionsAndWorldUUIDs = containersNode.getNode("chestrefill", "refillable-containers").getChildrenMap().keySet();
            for(final Object blockPositionAndWorldUUID : blockPositionsAndWorldUUIDs)
            {
                if(!(blockPositionAndWorldUUID instanceof String))
                    continue;
                final String blockPositionAndWorldUUIDString = String.valueOf(blockPositionAndWorldUUID);
                final Object kitValue = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUIDString, "kit").getValue();
                if(kitValue != null && String.valueOf(kitValue).equals(kitName))
                    containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUIDString, "kit").setValue("");
            }
            return true;
        }
        catch(Exception e)
        {
            e.printStackTrace();
            Sponge.getServer().getConsole().sendMessage(PluginInfo.ERROR_PREFIX.concat(Text.of("Could not remove kit from the storage. Kit name = " + kitName)));
        }

        return false;
    }

    @Override
    public boolean assignKit(ContainerLocation containerLocation, String kitName)
    {
        try
        {
            //We are using block position and recreating location on retrieval.
            String blockPositionAndWorldUUID = containerLocation.getBlockPosition().toString() + "|" + containerLocation.getWorldUUID();

            //Set chest's kit
            containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "kit").setValue(kitName);
            containersLoader.save(containersNode);
            return true;
        }
        catch(IOException e)
        {
            e.printStackTrace();
            Sponge.getServer().getConsole().sendMessage(PluginInfo.ERROR_PREFIX.concat(Text.of("Could not assign kit to the container location. Container location = " + containerLocation + " | Kit name = " + kitName)));
        }

        return false;
    }

    private Runnable checkFileUpdate()
    {
        return () ->
        {
            try
            {
                for (WatchEvent<?> event : key.pollEvents())
                {
                    final Path changedFilePath = (Path) event.context();
                    if (changedFilePath.getFileName().toString().equals("containers.json"))
                    {
                        Sponge.getServer().getConsole().sendMessage(Text.of(PluginInfo.PLUGIN_PREFIX, TextColors.YELLOW, "Detected changes in containers.json file. Reloading!"));
                        containersNode = containersLoader.load();
                        break;
                    }
                }
                key.reset();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        };
    }

    private RefillableContainer getRefillableContainerFromFile(ContainerLocation containerLocation)
    {
        try
        {
             final String blockPositionAndWorldUUID = containerLocation.getBlockPosition().toString() + "|" + containerLocation.getWorldUUID().toString();

            final Object containersName = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "name").getValue();
            String name = null;
            if (containersName != null) name = (String)containersName;

            final BlockType containerBlockType = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "container-block-type").getValue(TypeToken.of(BlockType.class));
            final String kitName = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "kit").getString("");
            List<RefillableItem> chestItems = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "items").getValue(REFILLABLE_TIME_LIST_TYPE_TOKEN);
            final int time = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "time").getInt();
            final boolean isOneItemAtTime = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "one-item-at-time").getBoolean();
            final boolean shouldReplaceExistingItems = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "replace-existing-items").getBoolean();
            final boolean hiddenIfNoItems = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "hidden-if-no-items").getBoolean();
            final BlockType hidingBlockType = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "hiding-block").getValue(TypeToken.of(BlockType.class));
            final String requiredPermission = containersNode.getNode("chestrefill", "refillable-containers", blockPositionAndWorldUUID, "required-permission").getString("");

            if(chestItems == null)
            {
                chestItems = new ArrayList<>();
            }

            return new RefillableContainer(name, containerLocation, containerBlockType, chestItems, time, isOneItemAtTime, shouldReplaceExistingItems, hiddenIfNoItems, hidingBlockType, kitName, requiredPermission);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Sponge.getServer().getConsole().sendMessage(PluginInfo.ERROR_PREFIX.concat(Text.of("Could not get a container from the storage. Container location = " + containerLocation)));
        }

        return null;
    }
}
