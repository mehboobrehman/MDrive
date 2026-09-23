import React, { useState, useEffect, useCallback } from 'react';
import { logToAgent, getServerConfig, setServerConfig, discoverServer, getRecentLogs, clearLogs } from './logger';

/**
 * DevMenuOverlay: A floating developer menu for MDrive telemetry and configuration.
 * Automatically handles both React Native and Web environments.
 */
export const DevMenuOverlay: React.FC = () => {
    const [isVisible, setIsVisible] = useState(false);
    const [activeTab, setActiveTab] = useState<'logs' | 'config'>('logs');
    const [config, setConfig] = useState(getServerConfig());
    const [logs, setLogs] = useState(getRecentLogs());
    const [isScanning, setIsScanning] = useState(false);
    const [inputIp, setInputIp] = useState(config.ip);
    const [inputPort, setInputPort] = useState(String(config.port));

    // Refresh logs and config periodically when visible
    useEffect(() => {
        if (!isVisible) return;
        
        const interval = setInterval(() => {
            setLogs(getRecentLogs());
            setConfig(getServerConfig());
        }, 1000);

        return () => clearInterval(interval);
    }, [isVisible]);

    const handleRescan = async () => {
        setIsScanning(true);
        const resolved = await discoverServer();
        setIsScanning(false);
        if (resolved) {
            const newCfg = getServerConfig();
            setConfig(newCfg);
            setInputIp(newCfg.ip);
            setInputPort(String(newCfg.port));
        }
    };

    const handleSaveConfig = async () => {
        await setServerConfig(inputIp, parseInt(inputPort, 10));
        setConfig(getServerConfig());
        logToAgent('INFO', `Server config updated manually: ${inputIp}:${inputPort}`);
    };

    const handleSendTestLog = () => {
        logToAgent('DEBUG', 'Test log message from DevMenuOverlay', undefined, { 
            timestamp: new Date().toISOString(),
            source: 'DevMenuOverlay'
        });
    };

    if (!isVisible) {
        return (
            <div 
                onClick={() => setIsVisible(true)}
                style={{
                    position: 'fixed',
                    bottom: 20,
                    right: 20,
                    width: 50,
                    height: 50,
                    borderRadius: 25,
                    backgroundColor: 'rgba(0, 122, 255, 0.8)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    color: 'white',
                    fontWeight: 'bold',
                    fontSize: 12,
                    cursor: 'pointer',
                    zIndex: 9999,
                    boxShadow: '0 4px 12px rgba(0,0,0,0.2)',
                    userSelect: 'none'
                }}
            >
                DEV
            </div>
        );
    }

    return (
        <div style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            backgroundColor: 'rgba(0,0,0,0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 10000,
            padding: 20,
            fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif'
        }}>
            <div style={{
                backgroundColor: '#fff',
                width: '100%',
                maxWidth: 500,
                height: '80%',
                maxHeight: 700,
                borderRadius: 12,
                display: 'flex',
                flexDirection: 'column',
                overflow: 'hidden',
                boxShadow: '0 8px 32px rgba(0,0,0,0.3)'
            }}>
                {/* Header */}
                <div style={{
                    padding: '15px 20px',
                    borderBottom: '1px solid #eee',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    backgroundColor: '#f8f9fa'
                }}>
                    <h3 style={{ margin: 0, fontSize: 18, color: '#333' }}>MDrive Dev Menu</h3>
                    <button 
                        onClick={() => setIsVisible(false)}
                        style={{
                            border: 'none',
                            background: 'none',
                            fontSize: 24,
                            cursor: 'pointer',
                            color: '#999'
                        }}
                    >
                        ×
                    </button>
                </div>

                {/* Tabs */}
                <div style={{ display: 'flex', borderBottom: '1px solid #eee' }}>
                    <button 
                        onClick={() => setActiveTab('logs')}
                        style={{
                            flex: 1,
                            padding: '12px',
                            border: 'none',
                            background: activeTab === 'logs' ? '#fff' : '#f0f0f0',
                            fontWeight: activeTab === 'logs' ? 'bold' : 'normal',
                            borderBottom: activeTab === 'logs' ? '2px solid #007aff' : 'none',
                            cursor: 'pointer'
                        }}
                    >
                        Logs ({logs.length})
                    </button>
                    <button 
                        onClick={() => setActiveTab('config')}
                        style={{
                            flex: 1,
                            padding: '12px',
                            border: 'none',
                            background: activeTab === 'config' ? '#fff' : '#f0f0f0',
                            fontWeight: activeTab === 'config' ? 'bold' : 'normal',
                            borderBottom: activeTab === 'config' ? '2px solid #007aff' : 'none',
                            cursor: 'pointer'
                        }}
                    >
                        Config
                    </button>
                </div>

                {/* Content */}
                <div style={{ flex: 1, overflowY: 'auto', padding: 15 }}>
                    {activeTab === 'logs' ? (
                        <div>
                            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
                                <button onClick={handleSendTestLog} style={btnStyle}>Send Test Log</button>
                                <button onClick={clearLogs} style={{ ...btnStyle, backgroundColor: '#ff3b30' }}>Clear Logs</button>
                            </div>
                            {logs.length === 0 ? (
                                <p style={{ textAlign: 'center', color: '#999', marginTop: 40 }}>No logs captured yet</p>
                            ) : (
                                logs.slice().reverse().map((log, i) => (
                                    <div key={i} style={{
                                        padding: '8px 0',
                                        borderBottom: '1px solid #f0f0f0',
                                        fontSize: 12,
                                        fontFamily: 'monospace'
                                    }}>
                                        <div style={{ color: '#888', marginBottom: 2 }}>
                                            {new Date(log.timestamp).toLocaleTimeString()} [{log.level}]
                                        </div>
                                        <div style={{ 
                                            color: log.level === 'ERROR' || log.level === 'FATAL' ? '#ff3b30' : 
                                                   log.level === 'WARN' ? '#ffcc00' : '#333',
                                            wordBreak: 'break-all'
                                        }}>
                                            {log.message}
                                        </div>
                                        {log.stack && (
                                            <pre style={{ 
                                                fontSize: 10, 
                                                color: '#666', 
                                                marginTop: 4, 
                                                overflowX: 'auto',
                                                backgroundColor: '#f8f8f8',
                                                padding: 5
                                            }}>
                                                {log.stack}
                                            </pre>
                                        )}
                                    </div>
                                ))
                            )
                            }
                        </div>
                    ) : (
                        <div>
                            <div style={formGroup}>
                                <label style={labelStyle}>Server URL:</label>
                                <div style={{ fontSize: 14, color: '#007aff', marginBottom: 15 }}>{config.url}</div>
                            </div>
                            <div style={formGroup}>
                                <label style={labelStyle}>Server IP:</label>
                                <input 
                                    type="text" 
                                    value={inputIp} 
                                    onChange={(e) => setInputIp(e.target.value)}
                                    style={inputStyle}
                                    placeholder="e.g. 192.168.1.100"
                                />
                            </div>
                            <div style={formGroup}>
                                <label style={labelStyle}>Server Port:</label>
                                <input 
                                    type="number" 
                                    value={inputPort} 
                                    onChange={(e) => setInputPort(e.target.value)}
                                    style={inputStyle}
                                    placeholder="8080"
                                />
                            </div>
                            <div style={{ display: 'flex', gap: 10, marginTop: 20 }}>
                                <button 
                                    onClick={handleSaveConfig} 
                                    style={{ ...btnStyle, flex: 1 }}
                                >
                                    Save Config
                                </button>
                                <button 
                                    onClick={handleRescan} 
                                    disabled={isScanning}
                                    style={{ ...btnStyle, flex: 1, backgroundColor: '#34c759' }}
                                >
                                    {isScanning ? 'Scanning...' : 'Auto Discover'}
                                </button>
                            </div>
                        </div>
                    )}
                </div>

                {/* Footer */}
                <div style={{
                    padding: '10px 15px',
                    backgroundColor: '#f8f9fa',
                    borderTop: '1px solid #eee',
                    fontSize: 11,
                    color: '#999',
                    textAlign: 'center'
                }}>
                    MDrive Dev Harness v1.0.0 • {config.resolved ? 'Connected' : 'Searching...'}
                </div>
            </div>
        </div>
    );
};

const btnStyle: React.CSSProperties = {
    padding: '8px 12px',
    borderRadius: 6,
    border: 'none',
    backgroundColor: '#007aff',
    color: '#fff',
    fontSize: 13,
    fontWeight: 'bold',
    cursor: 'pointer'
};

const inputStyle: React.CSSProperties = {
    width: '100%',
    padding: '10px',
    borderRadius: 6,
    border: '1px solid #ddd',
    boxSizing: 'border-box',
    fontSize: 14,
    marginTop: 5
};

const formGroup: React.CSSProperties = {
    marginBottom: 15
};

const labelStyle: React.CSSProperties = {
    fontSize: 12,
    fontWeight: 'bold',
    color: '#666'
};
