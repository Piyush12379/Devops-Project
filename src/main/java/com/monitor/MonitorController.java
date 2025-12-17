package com.monitor;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*") // Allow frontend to access this backend
public class MonitorController {

    private final SystemInfo si = new SystemInfo();
    private final HardwareAbstractionLayer hal = si.getHardware();
    private final OperatingSystem os = si.getOperatingSystem();

    // Variables to calculate network speed deltas
    private long lastRxBytes = 0;
    private long lastTxBytes = 0;
    private long lastTime = System.currentTimeMillis();

    @GetMapping("/processes")
    public List<Map<String, Object>> getProcesses() {
        // Fetch processes using OSHI
        List<OSProcess> processes = os.getProcesses(null, OperatingSystem.ProcessSorting.CPU_DESC, 0);
        
        return processes.stream().map(p -> {
            Map<String, Object> map = new HashMap<>();
            map.put("name", p.getName());
            map.put("pid", p.getProcessID());
            // Convert bytes to KB to match previous format
            map.put("memory", (p.getResidentSetSize() / 1024) + " KB"); 
            return map;
        }).collect(Collectors.toList());
    }

    @PostMapping("/kill")
    public Map<String, String> killProcess(@RequestBody Map<String, Integer> payload) {
        int pid = payload.get("pid");
        Map<String, String> response = new HashMap<>();
        
        try {
            // Using Runtime exec for Windows taskkill (matches original server.js behavior)
            // For cross-platform, you could assume standard 'kill' command or similar.
            String cmd = "taskkill /PID " + pid + " /F";
            Process process = Runtime.getRuntime().exec(cmd);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                response.put("message", "Process with PID " + pid + " terminated.");
            } else {
                throw new RuntimeException("Command failed");
            }
        } catch (Exception e) {
            // Fallback: Try OSHI kill if command fails or on non-Windows
            OSProcess p = os.getProcess(pid);
            if (p != null) {
                // Not all OS permissions allow this
                // boolean success = p.kill(); 
            }
            response.put("error", "Failed to terminate process: " + e.getMessage());
        }
        return response;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        GlobalMemory memory = hal.getMemory();
        CentralProcessor processor = hal.getProcessor();

        long totalMem = memory.getTotal();
        long availableMem = memory.getAvailable();
        long usedMem = totalMem - availableMem;

        Map<String, Object> data = new HashMap<>();
        
        // Memory formatting
        data.put("memoryUsage", String.format("%.2f", (double) usedMem / totalMem * 100));
        data.put("totalMemory", formatGB(totalMem));
        data.put("usedMemory", formatGB(usedMem));
        data.put("freeMemory", formatGB(availableMem));

        // CPU Per Core Usage
        // Note: OSHI requires a delay to calculate load. getProcessorCpuLoad(1000) waits 1s.
        // To avoid blocking, we can use getSystemCpuLoadBetweenTicks with stored ticks,
        // but for simplicity in this demo, we verify short samples.
        double[] loads = processor.getProcessorCpuLoad(500); // 500ms sample
        List<String> cpuUsageList = new ArrayList<>();
        for (double load : loads) {
            cpuUsageList.add(String.format("%.2f", load * 100));
        }
        data.put("cpuUsage", cpuUsageList);

        return data;
    }

    @GetMapping("/disk")
    public Map<String, Object> getDisk() {
        List<OSFileStore> fileStores = os.getFileSystem().getFileStores();
        Map<String, Object> data = new HashMap<>();

        if (!fileStores.isEmpty()) {
            OSFileStore store = fileStores.get(0); // Main drive
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            long used = total - free;

            data.put("totalDisk", formatGB(total));
            data.put("usedDisk", formatGB(used));
            data.put("freeDisk", formatGB(free));
        } else {
            data.put("error", "No disk found");
        }
        return data;
    }

    @GetMapping("/network")
    public Map<String, Object> getNetwork() {
        List<NetworkIF> networkIFs = hal.getNetworkIFs();
        
        long currentRx = 0;
        long currentTx = 0;
        
        for (NetworkIF net : networkIFs) {
            net.updateAttributes();
            currentRx += net.getBytesRecv();
            currentTx += net.getBytesSent();
        }

        long currentTime = System.currentTimeMillis();
        double timeDiffSec = (currentTime - lastTime) / 1000.0;
        
        if (timeDiffSec == 0) timeDiffSec = 1.0; // Avoid division by zero on first run

        double rxSpeed = (currentRx - lastRxBytes) / 1024.0 / timeDiffSec; // KB/s
        double txSpeed = (currentTx - lastTxBytes) / 1024.0 / timeDiffSec; // KB/s

        // Update state
        lastRxBytes = currentRx;
        lastTxBytes = currentTx;
        lastTime = currentTime;

        Map<String, Object> data = new HashMap<>();
        data.put("downloadSpeed", String.format("%.2f KB/s", rxSpeed));
        data.put("uploadSpeed", String.format("%.2f KB/s", txSpeed));
        
        return data;
    }

    @GetMapping("/cpu")
    public Map<String, Object> getCpuOverall() {
        CentralProcessor processor = hal.getProcessor();
        // 500ms sample for current load
        double load = processor.getSystemCpuLoad(500); 
        
        Map<String, Object> data = new HashMap<>();
        data.put("cpuUsage", String.format("%.2f", load * 100));
        return data;
    }

    private String formatGB(long bytes) {
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}