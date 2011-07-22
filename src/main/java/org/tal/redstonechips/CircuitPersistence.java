package org.tal.redstonechips;

import java.io.BufferedWriter;
import org.tal.redstonechips.circuit.Circuit;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.tal.redstonechips.circuit.InputPin;
import org.tal.redstonechips.util.ChunkLocation;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * A bunch of static methods for saving and loading circuit states.
 *
 * @author Tal Eisenberg
 */
public class CircuitPersistence {
    private RedstoneChips rc;

    public final static String circuitsFileExtension = ".circuits";
    public final static String circuitsFileName = "redstonechips"+circuitsFileExtension;

    private List<String> madeBackup = new ArrayList<String>();

    /**
     * Used to prevent saving state more than once per game tick.
     */
    private boolean dontSaveCircuits = false;

    private Runnable dontSaveCircuitsReset = new Runnable() {
        @Override
        public void run() {
            dontSaveCircuits = false;
        }
    };

    public CircuitPersistence(RedstoneChips plugin) {
        rc = plugin;
    }

    public void loadCircuits() {
        File file = getCircuitsFile();
        if (file.exists()) { // create empty file if doesn't already exist
            loadCircuitsFromFile(file,false);

//            try {
//                copy(file,new File(file.getParentFile(),circuitsFileName+".old"));
//                file.delete();
//            } catch(IOException e) {
//                e.printStackTrace();
//            }
            file.renameTo(new File(file.getParentFile(),circuitsFileName+".old"));
        }

        File[] dataFiles = rc.getDataFolder().listFiles(new FilenameFilter() {public boolean accept(File dir, String name) {return name.endsWith(circuitsFileExtension) && !name.equals(circuitsFileName);} });
        for(File dataFile : dataFiles) {
            loadCircuitsFromFile(dataFile,true);
        }

        rc.log(Level.INFO, "Done. Loaded " + rc.getCircuitManager().getCircuits().size() + " chips.");
    }

    public void loadCircuitsFromFile(File file, boolean checkForWorld) {
        if(checkForWorld) {
            String fileName=file.getName();
            String worldName=fileName.substring(0,fileName.length()-circuitsFileExtension.length());

            if(rc.getServer().getWorld(worldName)==null) {
                rc.log(Level.WARNING,"World "+worldName+" seems to be nonexistant while circuits for it do exist.");
                return;
            }
        }
        try {

            Yaml yaml = new Yaml();

            rc.log(Level.INFO, "Reading circuits file "+file.getName()+" ...");
            FileInputStream fis = new FileInputStream(file);
            List<Map<String, Object>> circuitsList = (List<Map<String, Object>>) yaml.load(fis);
            fis.close();

            rc.log(Level.INFO, "Activating circuits...");
            if (circuitsList!=null) {
                for (Map<String,Object> circuitMap : circuitsList) {
                    try {

                        compileCircuitFromMap(circuitMap);

                    } catch (IllegalArgumentException ie) {
                        rc.log(Level.WARNING, ie.getMessage() + ". Ignoring circuit.");
                        backupCircuitsFile(file.getName());
                        ie.printStackTrace();
                    } catch (InstantiationException ex) {
                        rc.log(Level.WARNING, ex.toString() + ". Ignoring circuit.");
                        backupCircuitsFile(file.getName());
                        ex.printStackTrace();
                    } catch (IllegalAccessException ex) {
                        rc.log(Level.WARNING, ex.toString() + ". Ignoring circuit.");
                        backupCircuitsFile(file.getName());
                        ex.printStackTrace();
                    } catch (Throwable t) {
                        rc.log(Level.SEVERE, t.toString() + ". Ignoring circuit.");
                        backupCircuitsFile(file.getName());
                        t.printStackTrace();
                    }
                }
            }
        } catch (IOException ex) {
            rc.log(Level.SEVERE, "Circuits file '" + file + "' threw error "+ex.toString()+".");
        }
    }

    public void saveCircuits() {
        if (dontSaveCircuits) return;
        
        rc.getCircuitManager().checkCircuitsIntegrity();

        Map<Integer, Circuit> circuits = rc.getCircuitManager().getCircuits();
        rc.log(Level.INFO, "Saving " + circuits.size() + " circuits state to file...");
        dontSaveCircuits = true;
        rc.getServer().getScheduler().scheduleAsyncDelayedTask(rc, dontSaveCircuitsReset, 1);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        Yaml yaml = new Yaml(options);
        HashMap<World,List<Map<String,Object>>> savedata = new HashMap<World,List<Map<String,Object>>>();
        List<Map<String,Object>> circuitMaps = null;

        for (Circuit c : circuits.values()) {
            World world = c.world;
            if(!savedata.containsKey(world)) {
                savedata.put(world, new ArrayList<Map<String,Object>>());
            }
            circuitMaps = savedata.get(world);
            c.save();
            circuitMaps.add(this.circuitToMap(c));
        }
        
        for(World wrld : savedata.keySet()) {
            try {
                File file = getCircuitsFile(wrld.getName()+circuitsFileExtension);
                circuitMaps = savedata.get(wrld);
                FileOutputStream fos = new FileOutputStream(file);
                yaml.dump(circuitMaps, new BufferedWriter(new OutputStreamWriter(fos, "UTF-8")));
                fos.flush();
                fos.close();
            } catch (IOException ex) {
                rc.log(Level.SEVERE, ex.getMessage());
            }
        }
    }

    private Map<String, Object> circuitToMap(Circuit c) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("class", c.getCircuitClass());
        map.put("world", c.world.getName());
        map.put("activationBlock", makeBlockList(c.activationBlock));
        map.put("chunk", makeChunksList(c.circuitChunks));
        map.put("inputs", makeInputPinsList(c.inputs));
        map.put("outputs", makeBlockListsList(c.outputs));
        map.put("interfaces", makeBlockListsList(c.interfaceBlocks));
        map.put("structure", makeBlockListsList(c.structure));
        map.put("signArgs", c.args);
        map.put("state", c.getInternalState());
        map.put("id", c.id);

        return map;
    }

    private Circuit compileCircuitFromMap(Map<String,Object> map) throws InstantiationException, IllegalAccessException {

        String className = (String)map.get("class");
        World world = findWorld((String)map.get("world"));
        Circuit c = rc.getCircuitLoader().getCircuitInstance(className);
        c.world = world;
        c.activationBlock = getLocation(world, (List<Integer>)map.get("activationBlock"));
        c.outputs = getLocationArray(world, (List<List<Integer>>)map.get("outputs"));
        c.interfaceBlocks = getLocationArray(world, (List<List<Integer>>)map.get("interfaces"));
        c.structure = getLocationArray(world, (List<List<Integer>>)map.get("structure"));
        c.inputs = getInputPinsArray((List<List<Integer>>)map.get("inputs"), c);
        
        if (map.containsKey("chunks")) {
            c.circuitChunks = getChunkLocations(world, (List<List<Integer>>)map.get("chunks"));
        } else {
            c.circuitChunks = rc.getCircuitManager().findCircuitChunks(c);
        }

        List<String> argsList = (List<String>)map.get("signArgs");
        String[] signArgs = argsList.toArray(new String[argsList.size()]);

        int id = -1;
        if (map.containsKey("id")) id = (Integer)map.get("id");

        if (rc.getCircuitManager().activateCircuit(c, null, signArgs, id)>0) {
            if (map.containsKey("state"))
                c.setInternalState((Map<String, String>)map.get("state"));
            
            return c;
        }
        else return null;
    }

    private List<Integer> makeBlockList(Location l) {
        List<Integer> list = new ArrayList<Integer>();
        list.add(l.getBlockX());
        list.add(l.getBlockY());
        list.add(l.getBlockZ());

        return list;
    }

    private Object makeChunksList(ChunkLocation[] locs) {
        List<List<Integer>> list = new ArrayList<List<Integer>>();
        for (ChunkLocation l : locs) {
            List<Integer> loc = new ArrayList<Integer>();
            loc.add(l.getX());
            loc.add(l.getZ());
            list.add(loc);
        }

        return list;
    }

    private Object makeInputPinsList(InputPin[] inputs) {
        List<List<Integer>> list = new ArrayList<List<Integer>>();
        for (InputPin p : inputs)
            list.add(makeBlockList(p.getInputBlock()));
        return list;
    }

    private Object makeBlockListsList(Location[] vs) {
        List<List<Integer>> list = new ArrayList<List<Integer>>();
        for (Location l : vs)
            list.add(makeBlockList(l));
        return list;
    }

    private World findWorld(String worldName) {
        World w = rc.getServer().getWorld(worldName);

        if (w!=null) return w;
        else throw new IllegalArgumentException("World " + worldName + " was not found on the server.");
    }

    private Location getLocation(World w, List<Integer> coords) {
        return new Location(w, coords.get(0), coords.get(1), coords.get(2));
    }

    private ChunkLocation[] getChunkLocations(World world, List<List<Integer>> locs) {
        List<ChunkLocation> ret = new ArrayList<ChunkLocation>();

        for (List<Integer> loc : locs) {
            ret.add(new ChunkLocation(loc.get(0), loc.get(1), world));
        }

        return ret.toArray(new ChunkLocation[ret.size()]);
    }

    private Location[] getLocationArray(World w, List<List<Integer>> list) {
        List<Location> locations = new ArrayList<Location>();
        for (List<Integer> coords : list)
            locations.add(getLocation(w, coords));

        return locations.toArray(new Location[locations.size()]);
    }

    private InputPin[] getInputPinsArray(List<List<Integer>> list, Circuit c) {
        List<InputPin> inputs = new ArrayList<InputPin>();
        for (int i=0; i<list.size(); i++) {
            List<Integer> coords = list.get(i);
            inputs.add(new InputPin(c, new Location(c.world, coords.get(0), coords.get(1), coords.get(2)), i));
        }

        return inputs.toArray(new InputPin[inputs.size()]);
    }

    private void backupCircuitsFile(String filename) {
        if (madeBackup.contains(filename)) return;

        try {
            File original = getCircuitsFile(filename);
            File backup = getBackupFileName(original.getParentFile(),filename);

            rc.log(Level.INFO, "An error occurred while loading circuits state. To make sure you won't lose any circuit data, a backup copy of "
                + circuitsFileName + " is being created. The backup can be found at " + backup.getPath());
            copy(original, backup);
        } catch (IOException ex) {
            rc.log(Level.SEVERE, "Error while trying to write backup file: " + ex);
        }
        madeBackup.add(filename);
    }

    private File getCircuitsFile() {
        return getCircuitsFile(circuitsFileName);
    }
    private File getCircuitsFile(String name) {
        return new File(rc.getDataFolder(), name);
    }

    private void copy(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);

        // Transfer bytes from in to out
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    private File getBackupFileName(File parentFile,String filename) {
        String ext = ".BACKUP";
        File backup;
        int idx = 0;

        do {
            backup = new File(parentFile, filename + ext + idx);
            idx++;
        } while (backup.exists());
        return backup;
    }
}
