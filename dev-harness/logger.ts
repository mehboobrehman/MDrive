/**
 * Standalone, strictly decoupled Telemetry Client Logger
 */

interface LogEntry {
    level: string;
    message: string;
    stack?: string;
    context?: Record<string, any>;
    timestamp: string;
}

interface ServerConfig {
    ip: string;
    port: number;
    resolved: boolean;
}

let memoryLogs: LogEntry[] = [];
let serverConfig: ServerConfig = {
    ip: '10.0.2.2', // default android emulator loopback
    port: 8080,
    resolved: false
};

// Generic storage abstraction that gracefully falls back
const getStorage = () => {
    try {
        if (typeof window !== 'undefined' && window.localStorage) {
            return {
                getItem: async (key: string) => window.localStorage.getItem(key),
                setItem: async (key: string, value: string) => window.localStorage.setItem(key, value)
            };
        }
    } catch (_) {}

    try {
        // Attempt dynamically requiring react-native async storage if in RN context
        const AsyncStorage = require('@react-native-async-storage/async-storage').default;
        if (AsyncStorage) {
            return {
                getItem: async (key: string) => AsyncStorage.getItem(key),
                setItem: async (key: string, value: string) => AsyncStorage.setItem(key, value)
            };
        }
    } catch (_) {}

    // Memory fallback
    const memStore: Record<string, string> = {};
    return {
        getItem: async (key: string) => memStore[key] || null,
        setItem: async (key: string, value: string) => { memStore[key] = value; }
    };
};

const storage = getStorage();

export async function initHarness(config?: { ip?: string, port?: number }) {
    if (config?.ip) {
        serverConfig.ip = config.ip;
        serverConfig.resolved = true;
    }
    if (config?.port) {
        serverConfig.port = config.port;
    }

    try {
        const savedIp = await storage.getItem('mdrive_telemetry_ip');
        const savedPort = await storage.getItem('mdrive_telemetry_port');
        if (savedIp) {
            serverConfig.ip = savedIp;
            serverConfig.resolved = true;
        }
        if (savedPort) {
            serverConfig.port = parseInt(savedPort, 10);
        }
    } catch (_) {}

    // Trigger non-blocking async background discovery
    if (!serverConfig.resolved) {
        discoverServer().catch(() => {});
    }
}

export function getServerConfig() {
    return { ...serverConfig, url: `http://${serverConfig.ip}:${serverConfig.port}` };
}

export async function setServerConfig(ip: string, port?: number) {
    serverConfig.ip = ip;
    if (port !== undefined) {
        serverConfig.port = port;
    }
    serverConfig.resolved = true;
    try {
        await storage.setItem('mdrive_telemetry_ip', ip);
        if (port !== undefined) {
            await storage.setItem('mdrive_telemetry_port', String(port));
        }
    } catch (_) {}
}

/**
 * Probes candidate endpoints to find the active telemetry log server
 */
export async function discoverServer(): Promise<string | null> {
    const ports = [8080, 8081];
    const candidateIps = [
        '10.0.2.2',      // Android Emulator host loopback
        'localhost',     // standard local
        '127.0.0.1'      // standard local loopback
    ];

    // Attempt to extract local subnet from window.location if available
    try {
        if (typeof window !== 'undefined' && window.location && window.location.hostname) {
            const host = window.location.hostname;
            if (host && host !== 'localhost' && host !== '127.0.0.1') {
                candidateIps.unshift(host);
            }
        }
    } catch (_) {}

    for (const port of ports) {
        for (const ip of candidateIps) {
            const success = await probeConfigEndpoint(ip, port);
            if (success) {
                await setServerConfig(ip, port);
                return ip;
            }
        }
    }

    // Attempt broader subnet discovery for typical networks (e.g., 192.168.1.X or 192.168.0.X)
    // We scan a small set of likely router/host IPs or common subnet gateways
    const gatewayCandidates = ['192.168.1.100', '192.168.1.50', '192.168.0.100', '192.168.0.50'];
    for (const ip of gatewayCandidates) {
        if (await probeConfigEndpoint(ip, 8080)) {
            await setServerConfig(ip, 8080);
            return ip;
        }
    }

    return null;
}

async function probeConfigEndpoint(ip: string, port: number): Promise<boolean> {
    const controller = new AbortController();
    const id = setTimeout(() => controller.abort(), 1000); // 1s quick probe

    try {
        const response = await fetch(`http://${ip}:${port}/api/config`, {
            method: 'GET',
            signal: controller.signal,
            headers: { 'Accept': 'application/json' }
        });
        clearTimeout(id);
        if (response.ok) {
            const data = await response.json();
            return data.status === 'ok';
        }
    } catch (_) {
        clearTimeout(id);
    }
    return false;
}

/**
 * Non-blocking, highly resilient transmission of log to telemetry server
 */
export async function logToAgent(
    level: string,
    message: string,
    stack?: string,
    context?: Record<string, any>
): Promise<void> {
    const entry: LogEntry = {
        level: level.toUpperCase(),
        message,
        stack,
        context,
        timestamp: new Date().toISOString()
    };

    // Cache log entry in local memory stream
    memoryLogs.push(entry);
    if (memoryLogs.length > 500) {
        memoryLogs.shift(); // Bound memory logs size to 500 entries
    }

    // Persist log entry locally
    try {
        const key = `mdrive_pending_logs`;
        const pendingStr = await storage.getItem(key);
        const pending: LogEntry[] = pendingStr ? JSON.parse(pendingStr) : [];
        pending.push(entry);
        if (pending.length > 100) {
            pending.shift(); // Bound persistent logs to 100 entries
        }
        await storage.setItem(key, JSON.stringify(pending));
    } catch (_) {}

    // Dispatch log payload asynchronously to telemetry server with strict 2s timeout
    const url = `http://${serverConfig.ip}:${serverConfig.port}/api/log`;
    
    // Non-blocking invocation via self-executing async promise
    (async () => {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2000);

        try {
            const response = await fetch(url, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(entry),
                signal: controller.signal
            });
            clearTimeout(timeoutId);

            if (response.ok) {
                // Remove from pending storage logs on success
                try {
                    const key = `mdrive_pending_logs`;
                    const pendingStr = await storage.getItem(key);
                    if (pendingStr) {
                        let pending: LogEntry[] = JSON.parse(pendingStr);
                        pending = pending.filter(e => e.timestamp !== entry.timestamp);
                        await storage.setItem(key, JSON.stringify(pending));
                    }
                } catch (_) {}
            }
        } catch (_) {
            clearTimeout(timeoutId);
            // Silently swallow errors during background dispatch
        }
    })();
}

export function getRecentLogs(): LogEntry[] {
    return [...memoryLogs];
}

export function clearLogs(): void {
    memoryLogs = [];
    storage.setItem('mdrive_pending_logs', '[]').catch(() => {});
}
