
import React, { useState, useEffect } from 'react';
import { QRCodeSVG } from 'qrcode.react';

import StatusBar from './components/StatusBar';
import DevicePanel from './components/DevicePanel';

interface AppState {
    signalingPort: number;
    obsSourcePort: number;
    connectedDevices: number;
    signalingRunning: boolean;
    lanAddresses: string[];
    loading: boolean;
    error: string | null;
}

export default function App() {
    const [appState, setAppState] = useState<AppState>({
        signalingPort: 8765,
        obsSourcePort: 8766,
        connectedDevices: 0,
        signalingRunning: false,
        lanAddresses: [],
        loading: true,
        error: null,
    });

    const [selectedAddress, setSelectedAddress] = useState('');
    const [copied, setCopied] = useState(false);

    useEffect(() => {
        let active = true;

        async function loadConnectionInfo() {
            try {
                const info = await window.mahanaim.getConnectionInfo();

                if (!active) return;

                setAppState(prev => ({
                    ...prev,
                    signalingPort: info.signalingPort,
                    obsSourcePort: info.obsSourcePort,
                    signalingRunning: info.signalingRunning,
                    lanAddresses: info.lanAddresses,
                    loading: false,
                    error: null,
                }));

                setSelectedAddress(info.lanAddresses[0] ?? '');
            } catch (error) {
                if (!active) return;

                console.error('[Renderer] Failed to retrieve connection information:', error);

                setAppState(prev => ({
                    ...prev,
                    loading: false,
                    error: 'Unable to retrieve desktop connection information.',
                }));
            }
        }

        loadConnectionInfo();

        return () => {
            active = false;
        };
    }, []);

    const connectionUrl = selectedAddress
        ? `ws://${selectedAddress}:${appState.signalingPort}`
        : '';

    const pairingPayload = connectionUrl
        ? JSON.stringify({
            type: 'MAHANAIM_CAM_PAIR',
            version: 1,
            url: connectionUrl,
        })
        : '';

    async function copyConnectionAddress() {
        if (!connectionUrl) return;

        try {
            await navigator.clipboard.writeText(connectionUrl);
            setCopied(true);
            window.setTimeout(() => setCopied(false), 2000);
        } catch (error) {
            console.error('[Renderer] Clipboard copy failed:', error);
        }
    }

    return (
        <div className="app-layout">
            <header className="app-header">
                <div className="app-header__logo">
                    <div className="app-header__logo-icon">📷</div>
                    <span className="app-header__logo-text">MAHANAIM CAM</span>
                    <span className="app-header__logo-version">v0.1.0</span>
                </div>
            </header>

            <main className="app-main">
                <aside className="sidebar">
                    <DevicePanel connectedDevices={appState.connectedDevices} />
                </aside>

                <section className="content-area">
                    <div className="stream-view">
                        <div className="stream-view__placeholder">
                            <div className="stream-view__icon">📡</div>

                            <div className="stream-view__title">
                                Connect Your Android Phone
                            </div>

                            <div className="stream-view__subtitle">
                                Scan this QR code using MAHANAIM CAM on your Android phone.
                                Make sure both devices are connected to the same network.
                            </div>

                            {appState.loading ? (
                                <p>Loading connection information...</p>
                            ) : appState.error ? (
                                <p>{appState.error}</p>
                            ) : (
                                <div style={{ marginTop: '24px' }}>
                                    <p>
                                        Desktop Server:{' '}
                                        {appState.signalingRunning ? 'Running' : 'Offline'}
                                    </p>

                                    {appState.signalingRunning && selectedAddress ? (
                                        <>
                                            {appState.lanAddresses.length > 1 && (
                                                <div style={{ margin: '16px 0' }}>
                                                    <label htmlFor="network-address">
                                                        Select network:
                                                    </label>
                                                    <br />
                                                    <select
                                                        id="network-address"
                                                        value={selectedAddress}
                                                        onChange={event =>
                                                            setSelectedAddress(event.target.value)
                                                        }
                                                        style={{ marginTop: '8px', padding: '8px' }}
                                                    >
                                                        {appState.lanAddresses.map(address => (
                                                            <option key={address} value={address}>
                                                                {address}
                                                            </option>
                                                        ))}
                                                    </select>
                                                </div>
                                            )}

                                            <div style={{
                                                display: 'flex',
                                                flexDirection: 'column',
                                                alignItems: 'center',
                                                gap: '12px',
                                                marginTop: '20px',
                                            }}>
                                                <div style={{
                                                    background: '#ffffff',
                                                    padding: '16px',
                                                    borderRadius: '12px',
                                                }}>
                                                    <QRCodeSVG
                                                        value={pairingPayload}
                                                        size={220}
                                                        level="M"
                                                        includeMargin
                                                    />
                                                </div>

                                                <p style={{ fontWeight: 600 }}>
                                                    {connectionUrl}
                                                </p>

                                                <button
                                                    onClick={copyConnectionAddress}
                                                    style={{
                                                        padding: '10px 18px',
                                                        borderRadius: '8px',
                                                        border: 'none',
                                                        cursor: 'pointer',
                                                    }}
                                                >
                                                    {copied ? 'Copied!' : 'Copy Connection Address'}
                                                </button>

                                                <p style={{ fontSize: '13px', opacity: 0.7 }}>
                                                    WebSocket Port: {appState.signalingPort}
                                                </p>
                                            </div>
                                        </>
                                    ) : (
                                        <p>
                                            No active LAN connection detected. Check your
                                            network connection and restart the desktop app.
                                        </p>
                                    )}

                                    <p style={{ marginTop: '20px', fontSize: '13px' }}>
                                        Android QR scanning and pairing will be implemented next.
                                    </p>
                                </div>
                            )}
                        </div>
                    </div>
                </section>
            </main>

            <StatusBar
                signalingPort={appState.signalingPort}
                obsSourcePort={appState.obsSourcePort}
                signalingRunning={appState.signalingRunning}
                connectedDevices={appState.connectedDevices}
            />
        </div>
    );
}
