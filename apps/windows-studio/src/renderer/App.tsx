
import React, { useEffect, useRef, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import type { SignalingMessage } from '@mahanaim-cam/protocol';

import StatusBar from './components/StatusBar';

interface AppState {
    signalingPort: number;
    obsSourcePort: number;
    connectedDevices: number;
    signalingRunning: boolean;
    lanAddresses: string[];
    loading: boolean;
    error: string | null;
}

interface StreamStats {
    width: number;
    height: number;
    fps: number;
    bitrate: number;
    packetsLost: number;
    jitter: number;
    framesDecoded: number;
}

const initialStats: StreamStats = {
    width: 0,
    height: 0,
    fps: 0,
    bitrate: 0,
    packetsLost: 0,
    jitter: 0,
    framesDecoded: 0,
};

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
    const [pairedDevice, setPairedDevice] = useState('');
    const [streamStatus, setStreamStatus] = useState(
        'Waiting for Android connection'
    );

    const [stats, setStats] = useState<StreamStats>(initialStats);

    const [brightness, setBrightness] = useState(100);
    const [contrast, setContrast] = useState(100);
    const [saturation, setSaturation] = useState(100);
    const [zoom, setZoom] = useState(1);

    const [autoReconnect, setAutoReconnect] = useState(false);
    const [showStats, setShowStats] = useState(true);

    const videoRef = useRef<HTMLVideoElement>(null);
    const peerConnectionRef = useRef<RTCPeerConnection | null>(null);
    const sessionIdRef = useRef<string | null>(null);
    const pendingIceCandidatesRef = useRef<RTCIceCandidateInit[]>([]);

    const previousBytesRef = useRef(0);
    const previousTimestampRef = useRef(0);

    // --------------------------------------------------
    // LOAD DESKTOP CONNECTION INFORMATION
    // --------------------------------------------------

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

                console.error('[Renderer] Connection information failed:', error);

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

    // --------------------------------------------------
    // CLOSE WEBRTC
    // --------------------------------------------------

    function closeWebRTC() {
        const peerConnection = peerConnectionRef.current;

        if (peerConnection) {
            peerConnection.ontrack = null;
            peerConnection.onicecandidate = null;
            peerConnection.onconnectionstatechange = null;
            peerConnection.close();
        }

        peerConnectionRef.current = null;
        sessionIdRef.current = null;
        pendingIceCandidatesRef.current = [];

        previousBytesRef.current = 0;
        previousTimestampRef.current = 0;

        setStats(initialStats);

        if (videoRef.current) {
            videoRef.current.srcObject = null;
        }
    }

    // --------------------------------------------------
    // START WEBRTC
    // --------------------------------------------------

    async function startWebRTC(sessionId: string) {
        try {
            console.log('[WebRTC] Starting session:', sessionId);

            closeWebRTC();

            sessionIdRef.current = sessionId;

            const peerConnection = new RTCPeerConnection({
                iceServers: [],
            });

            peerConnectionRef.current = peerConnection;

            peerConnection.ontrack = event => {
                console.log('[WebRTC] Remote video track received');

                const stream = event.streams[0] ??
                    new MediaStream([event.track]);

                if (videoRef.current) {
                    videoRef.current.srcObject = stream;
                    void videoRef.current.play().catch(error => {
                        console.warn('[WebRTC] Video autoplay:', error);
                    });
                }

                setStreamStatus('Receiving camera stream');
            };

            peerConnection.onicecandidate = event => {
                if (!event.candidate) return;

                if (sessionIdRef.current !== sessionId) return;

                const message: SignalingMessage = {
                    type: 'ICE_CANDIDATE',
                    sessionId,
                    candidate: event.candidate.candidate,
                    sdpMid: event.candidate.sdpMid ?? '',
                    sdpMLineIndex: event.candidate.sdpMLineIndex ?? 0,
                };

                void window.mahanaim.sendSignalingMessage(message)
                    .then(sent => {
                        if (!sent) {
                            console.warn('[WebRTC] ICE candidate was not sent');
                        }
                    })
                    .catch(error => {
                        console.error('[WebRTC] ICE send failed:', error);
                    });
            };

            peerConnection.onconnectionstatechange = () => {
                const state = peerConnection.connectionState;

                console.log('[WebRTC] Connection state:', state);

                switch (state) {
                    case 'connected':
                        setStreamStatus('Video connection established');
                        break;
                    case 'connecting':
                        setStreamStatus('Establishing video connection...');
                        break;
                    case 'disconnected':
                        setStreamStatus('Connection interrupted');
                        break;
                    case 'failed':
                        setStreamStatus('Video connection failed');
                        break;
                    case 'closed':
                        setStreamStatus('Video connection closed');
                        break;
                }
            };

            peerConnection.addTransceiver('video', {
                direction: 'recvonly',
            });

            const offer = await peerConnection.createOffer();

            await peerConnection.setLocalDescription(offer);

            if (sessionIdRef.current !== sessionId) return;

            const message: SignalingMessage = {
                type: 'SDP_OFFER',
                sessionId,
                sdp: offer.sdp ?? '',
            };

            const sent = await window.mahanaim.sendSignalingMessage(message);

            if (!sent) {
                throw new Error('Unable to send WebRTC offer.');
            }

            setStreamStatus('Waiting for Android video response...');

        } catch (error) {
            console.error('[WebRTC] Failed to start:', error);
            setStreamStatus('Unable to start video connection');
        }
    }

    // --------------------------------------------------
    // ANDROID SIGNALING MESSAGES
    // --------------------------------------------------

    useEffect(() => {
        const unsubscribe = window.mahanaim.onSignalingMessage(
            async (message: SignalingMessage) => {
                console.log('[Renderer] Signaling:', message.type);

                switch (message.type) {
                    case 'PAIR_REQUEST': {
                        setPairedDevice(message.deviceName);

                        setAppState(prev => ({
                            ...prev,
                            connectedDevices: 1,
                        }));

                        setStreamStatus('Android paired. Preparing video...');

                        break;
                    }

                    case 'PAIR_ACCEPT': {
                        setStreamStatus('Pairing successful. Starting WebRTC...');
                        await startWebRTC(message.sessionId);
                        break;
                    }

                    case 'SDP_ANSWER': {
                        const peerConnection = peerConnectionRef.current;

                        if (
                            !peerConnection ||
                            message.sessionId !== sessionIdRef.current
                        ) {
                            return;
                        }

                        try {
                            await peerConnection.setRemoteDescription({
                                type: 'answer',
                                sdp: message.sdp,
                            });

                            const pending = pendingIceCandidatesRef.current;
                            pendingIceCandidatesRef.current = [];

                            for (const candidate of pending) {
                                await peerConnection.addIceCandidate(candidate);
                            }

                            setStreamStatus('WebRTC answer received. Connecting...');
                        } catch (error) {
                            console.error('[WebRTC] SDP answer failed:', error);
                            setStreamStatus('Failed to process video response');
                        }

                        break;
                    }

                    case 'ICE_CANDIDATE': {
                        if (message.sessionId !== sessionIdRef.current) return;

                        const candidate: RTCIceCandidateInit = {
                            candidate: message.candidate,
                            sdpMid: message.sdpMid,
                            sdpMLineIndex: message.sdpMLineIndex,
                        };

                        const peerConnection = peerConnectionRef.current;

                        if (!peerConnection || !peerConnection.remoteDescription) {
                            pendingIceCandidatesRef.current.push(candidate);
                            break;
                        }

                        try {
                            await peerConnection.addIceCandidate(candidate);
                        } catch (error) {
                            console.error('[WebRTC] ICE candidate failed:', error);
                        }

                        break;
                    }

                    case 'SESSION_END': {
                        if (message.sessionId !== sessionIdRef.current) return;

                        closeWebRTC();
                        setPairedDevice('');

                        setAppState(prev => ({
                            ...prev,
                            connectedDevices: 0,
                        }));

                        setStreamStatus('Android disconnected');
                        break;
                    }

                    default:
                        break;
                }
            }
        );

        return unsubscribe;
    }, []);

    // --------------------------------------------------
    // WEBRTC RECEIVER STATISTICS
    // --------------------------------------------------

    useEffect(() => {
        const timer = window.setInterval(async () => {
            const connection = peerConnectionRef.current;

            if (!connection || connection.connectionState !== 'connected') {
                return;
            }

            try {
                const report = await connection.getStats();

                let receivedBytes = 0;
                let timestamp = 0;

                const nextStats: StreamStats = {
                    ...initialStats,
                };

                report.forEach(stat => {
                    if (
                        stat.type === 'inbound-rtp' &&
                        stat.kind === 'video' &&
                        !stat.isRemote
                    ) {
                        nextStats.width = stat.frameWidth ?? 0;
                        nextStats.height = stat.frameHeight ?? 0;
                        nextStats.fps = stat.framesPerSecond ?? 0;
                        nextStats.packetsLost = stat.packetsLost ?? 0;
                        nextStats.jitter = stat.jitter
                            ? stat.jitter * 1000
                            : 0;
                        nextStats.framesDecoded = stat.framesDecoded ?? 0;

                        receivedBytes = stat.bytesReceived ?? 0;
                        timestamp = stat.timestamp ?? 0;
                    }
                });

                if (previousTimestampRef.current > 0 && timestamp > previousTimestampRef.current) {
                    const elapsed = (timestamp - previousTimestampRef.current) / 1000;
                    const byteDifference = receivedBytes - previousBytesRef.current;

                    nextStats.bitrate = Math.max(
                        0,
                        Math.round((byteDifference * 8) / elapsed / 1000)
                    );
                }

                previousBytesRef.current = receivedBytes;
                previousTimestampRef.current = timestamp;

                setStats(nextStats);
            } catch (error) {
                console.warn('[WebRTC] Statistics unavailable:', error);
            }
        }, 1000);

        return () => window.clearInterval(timer);
    }, []);

    // --------------------------------------------------
    // COMPONENT CLEANUP
    // --------------------------------------------------

    useEffect(() => {
        return () => closeWebRTC();
    }, []);

    // --------------------------------------------------
    // QR CODE
    // --------------------------------------------------

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

    // --------------------------------------------------
    // PREVIEW ADJUSTMENTS
    // --------------------------------------------------

    const previewFilter = [
        `brightness(${brightness}%)`,
        `contrast(${contrast}%)`,
        `saturate(${saturation}%)`,
    ].join(' ');

    const previewTransform = `scale(${zoom})`;

    function resetAdjustments() {
        setBrightness(100);
        setContrast(100);
        setSaturation(100);
        setZoom(1);
    }

    const isStreaming = Boolean(pairedDevice);

    // --------------------------------------------------
    // UI
    // --------------------------------------------------

    return (
        <div className="app-layout">

            {/* TOP HEADER */}

            <header className="app-header">
                <div className="brand">
                    <div className="brand__mark">✦</div>
                    <div>
                        <div className="brand__name">MAHANAIM CAM</div>
                        <div className="brand__subtitle">DESKTOP STUDIO</div>
                    </div>
                </div>

                <div className="header-center">
                    <span className="header-center__dot" />
                    {isStreaming ? 'CAMERA CONNECTED' : 'READY TO CONNECT'}
                </div>

                <div className="header-version">v0.2.0</div>
            </header>

            {/* THREE-PANEL WORKSPACE */}

            <main className="studio-layout">

                {/* LEFT: CONNECTION MANAGER */}

                <aside className="panel connection-panel">

                    <div className="panel-heading">
                        <div>
                            <span className="eyebrow">CONNECTION</span>
                            <h2>Connection manager</h2>
                        </div>
                        <span className="heading-icon">⌁</span>
                    </div>

                    <div className="connection-mode">
                        <span className="connection-mode__icon">⌁</span>
                        <div>
                            <strong>Wi-Fi connection</strong>
                            <small>Local network</small>
                        </div>
                        <span className="mode-indicator">●</span>
                    </div>

                    {appState.lanAddresses.length > 1 && (
                        <div className="field-group">
                            <label htmlFor="network-address">NETWORK INTERFACE</label>
                            <select
                                id="network-address"
                                value={selectedAddress}
                                onChange={event => setSelectedAddress(event.target.value)}
                            >
                                {appState.lanAddresses.map(address => (
                                    <option key={address} value={address}>
                                        {address}
                                    </option>
                                ))}
                            </select>
                        </div>
                    )}

                    <div className="connection-address">
                        <span className="eyebrow">DESKTOP ADDRESS</span>
                        <strong>{selectedAddress || 'Detecting network...'}</strong>
                        <small>Port {appState.signalingPort}</small>
                    </div>

                    <div className="connection-status">
                        <span className={`status-pill ${isStreaming ? 'status-pill--online' : ''}`}>
                            <span />
                            {isStreaming ? 'CONNECTED' : appState.signalingRunning ? 'SERVER READY' : 'OFFLINE'}
                        </span>
                    </div>

                    <div className="qr-section">
                        <div className="qr-section__heading">PAIR YOUR PHONE</div>

                        {appState.loading ? (
                            <div className="qr-placeholder">Loading...</div>
                        ) : appState.error ? (
                            <div className="qr-placeholder qr-placeholder--error">
                                {appState.error}
                            </div>
                        ) : appState.signalingRunning && pairingPayload ? (
                            <div className="qr-code">
                                <QRCodeSVG
                                    value={pairingPayload}
                                    size={184}
                                    level="M"
                                    includeMargin
                                />
                            </div>
                        ) : (
                            <div className="qr-placeholder">
                                Desktop signaling server unavailable.
                            </div>
                        )}

                        <p>Open MAHANAIM CAM on your Android phone and scan this code.</p>

                        <button
                            className="button button--secondary button--full"
                            onClick={copyConnectionAddress}
                            disabled={!connectionUrl}
                        >
                            {copied ? '✓ Copied' : 'Copy connection address'}
                        </button>
                    </div>

                    <div className="connected-device">
                        <span className="eyebrow">CONNECTED DEVICE</span>

                        {isStreaming ? (
                            <div className="device-info">
                                <div className="device-avatar">▣</div>
                                <div>
                                    <strong>{pairedDevice}</strong>
                                    <small>{streamStatus}</small>
                                </div>
                                <span className="device-online" />
                            </div>
                        ) : (
                            <div className="device-empty">
                                <span>▣</span>
                                <p>No phone connected yet</p>
                            </div>
                        )}
                    </div>

                    <div className="connection-footer">
                        <span>LOCAL NETWORK ONLY</span>
                        <span>● PRIVATE</span>
                    </div>
                </aside>

                {/* CENTRE: LIVE STUDIO */}

                <section className="studio-center">

                    <div className="studio-titlebar">
                        <div>
                            <span className="eyebrow">PRODUCTION MONITOR</span>
                            <h1>Live Studio</h1>
                        </div>

                        <div className={`live-badge ${isStreaming ? 'live-badge--active' : ''}`}>
                            <span />
                            {isStreaming ? 'LIVE STREAMING' : 'STANDBY'}
                        </div>
                    </div>

                    <div className="video-card">

                        <div className="video-viewport">
                            <video
                                ref={videoRef}
                                autoPlay
                                playsInline
                                muted
                                className="live-video"
                                style={{
                                    display: isStreaming ? 'block' : 'none',
                                    filter: previewFilter,
                                    transform: previewTransform,
                                }}
                            />

                            {!isStreaming && (
                                <div className="video-placeholder">
                                    <div className="placeholder-symbol">✦</div>
                                    <h2>MAHANAIM CAM</h2>
                                    <p>Your phone camera will appear here.</p>

                                    <div className="placeholder-instruction">
                                        Scan the QR code on the left to connect your phone.
                                    </div>

                                    {appState.error && (
                                        <p className="error-message">{appState.error}</p>
                                    )}
                                </div>
                            )}

                            {isStreaming && (
                                <>
                                    <div className="video-overlay video-overlay--top">
                                        <div className="device-label">
                                            <span className="device-online" />
                                            {pairedDevice}
                                        </div>
                                        <div className="video-state">
                                            {streamStatus}
                                        </div>
                                    </div>

                                    {showStats && (
                                        <div className="stats-overlay">
                                            <div className="stats-overlay__title">
                                                LIVE STREAM STATS
                                            </div>
                                            <div>Resolution: {stats.width || '—'} × {stats.height || '—'}</div>
                                            <div>Frame rate: {stats.fps.toFixed(1)} FPS</div>
                                            <div>Bitrate: {stats.bitrate} kbps</div>
                                            <div>Packets lost: {stats.packetsLost}</div>
                                            <div>Jitter: {stats.jitter.toFixed(1)} ms</div>
                                            <div>Frames decoded: {stats.framesDecoded}</div>
                                        </div>
                                    )}
                                </>
                            )}
                        </div>

                        <div className="video-toolbar">
                            <div className="video-toolbar__left">
                                <span className="toolbar-indicator" />
                                <span>{isStreaming ? 'Receiving video over WebRTC' : 'Waiting for video source'}</span>
                            </div>

                            <button
                                className="text-button"
                                onClick={() => setShowStats(value => !value)}
                            >
                                {showStats ? 'Hide stats' : 'Show stats'}
                            </button>
                        </div>
                    </div>

                    {/* QUICK ACTIONS */}

                    <div className="quick-actions panel">
                        <div className="panel-heading">
                            <div>
                                <span className="eyebrow">WORKSPACE</span>
                                <h2>Quick actions</h2>
                            </div>
                        </div>

                        <div className="quick-actions__grid">
                            <button
                                className="quick-action"
                                onClick={resetAdjustments}
                            >
                                <span className="quick-action__icon">⟲</span>
                                <strong>Reset preview</strong>
                                <small>Restore adjustments</small>
                            </button>

                            <button
                                className="quick-action"
                                onClick={() => setShowStats(value => !value)}
                            >
                                <span className="quick-action__icon">▤</span>
                                <strong>Stream stats</strong>
                                <small>{showStats ? 'Hide overlay' : 'Show overlay'}</small>
                            </button>

                            <button
                                className="quick-action"
                                onClick={() => setZoom(value => Math.min(2, value + 0.1))}
                                disabled={!isStreaming}
                            >
                                <span className="quick-action__icon">⌕</span>
                                <strong>Preview zoom</strong>
                                <small>Desktop preview only</small>
                            </button>

                            <button
                                className="quick-action"
                                onClick={() => setZoom(1)}
                                disabled={!isStreaming}
                            >
                                <span className="quick-action__icon">⊙</span>
                                <strong>Fit preview</strong>
                                <small>Return to normal view</small>
                            </button>
                        </div>
                    </div>
                </section>

                {/* RIGHT: VIDEO ADJUSTMENTS */}

                <aside className="right-panel">

                    <section className="panel adjustment-panel">
                        <div className="panel-heading">
                            <div>
                                <span className="eyebrow">IMAGE</span>
                                <h2>Video adjust</h2>
                            </div>
                            <span className="heading-icon">☷</span>
                        </div>

                        <p className="panel-description">
                            Adjust the desktop monitor preview.
                        </p>

                        <AdjustmentSlider
                            label="Brightness"
                            value={brightness}
                            min={50}
                            max={150}
                            onChange={setBrightness}
                        />

                        <AdjustmentSlider
                            label="Contrast"
                            value={contrast}
                            min={50}
                            max={150}
                            onChange={setContrast}
                        />

                        <AdjustmentSlider
                            label="Saturation"
                            value={saturation}
                            min={0}
                            max={200}
                            onChange={setSaturation}
                        />

                        <button
                            className="text-button adjustment-reset"
                            onClick={resetAdjustments}
                        >
                            Reset adjustments
                        </button>

                        <div className="preview-only-note">
                            These adjustments affect only the desktop preview.
                        </div>
                    </section>

                    <section className="panel camera-panel">
                        <div className="panel-heading">
                            <div>
                                <span className="eyebrow">DEVICE</span>
                                <h2>Camera controls</h2>
                            </div>
                        </div>

                        <div className="camera-control-grid">
                            <div className="camera-control">
                                <span>✥</span>
                                <strong>Pan</strong>
                                <small>Coming soon</small>
                            </div>
                            <div className="camera-control">
                                <span>⌕</span>
                                <strong>Zoom</strong>
                                <small>Phone control</small>
                            </div>
                            <div className="camera-control">
                                <span>⊙</span>
                                <strong>Focus</strong>
                                <small>Coming soon</small>
                            </div>
                        </div>

                        <div className="control-note">
                            Camera hardware controls will be sent to the connected Android device in a later update.
                        </div>
                    </section>

                    <section className="panel output-panel">
                        <div className="panel-heading">
                            <div>
                                <span className="eyebrow">INTEGRATION</span>
                                <h2>Output settings</h2>
                            </div>
                        </div>

                        <div className="output-item">
                            <div>
                                <strong>OBS source server</strong>
                                <small>localhost:{appState.obsSourcePort}</small>
                            </div>
                            <span className="output-status">READY</span>
                        </div>

                        <div className="output-item">
                            <div>
                                <strong>Auto reconnect</strong>
                                <small>Interface preference only</small>
                            </div>
                            <Toggle
                                checked={autoReconnect}
                                onChange={setAutoReconnect}
                            />
                        </div>

                        <div className="output-item">
                            <div>
                                <strong>Show stream stats</strong>
                                <small>Monitor overlay</small>
                            </div>
                            <Toggle
                                checked={showStats}
                                onChange={setShowStats}
                            />
                        </div>

                        <div className="output-address">
                            <span className="eyebrow">OBS BROWSER SOURCE</span>
                            <code>http://localhost:{appState.obsSourcePort}</code>
                        </div>
                    </section>

                    <section className="panel session-panel">
                        <div className="eyebrow">SESSION</div>
                        <div className="session-state">
                            <span className={isStreaming ? 'session-dot session-dot--active' : 'session-dot'} />
                            {isStreaming ? 'Phone connected' : 'No active session'}
                        </div>

                        {isStreaming && (
                            <button
                                className="button button--danger button--full"
                                onClick={() => {
                                    const sessionId = sessionIdRef.current;

                                    if (sessionId) {
                                        const message: SignalingMessage = {
                                            type: 'SESSION_END',
                                            sessionId,
                                        };

                                        void window.mahanaim.sendSignalingMessage(message);
                                    }

                                    closeWebRTC();
                                    setPairedDevice('');

                                    setAppState(prev => ({
                                        ...prev,
                                        connectedDevices: 0,
                                    }));

                                    setStreamStatus('Session ended');
                                }}
                            >
                                Disconnect device
                            </button>
                        )}
                    </section>
                </aside>
            </main>

            {/* BOTTOM STATUS BAR */}

            <StatusBar
                signalingPort={appState.signalingPort}
                obsSourcePort={appState.obsSourcePort}
                signalingRunning={appState.signalingRunning}
                connectedDevices={appState.connectedDevices}
            />
        </div>
    );
}

// --------------------------------------------------
// ADJUSTMENT SLIDER
// --------------------------------------------------

function AdjustmentSlider({
    label,
    value,
    min,
    max,
    onChange,
}: {
    label: string;
    value: number;
    min: number;
    max: number;
    onChange: (value: number) => void;
}) {
    return (
        <div className="adjustment-slider">
            <div className="adjustment-slider__heading">
                <label>{label}</label>
                <span>{value}%</span>
            </div>

            <input
                type="range"
                min={min}
                max={max}
                value={value}
                onChange={event => onChange(Number(event.target.value))}
            />
        </div>
    );
}

// --------------------------------------------------
// TOGGLE
// --------------------------------------------------

function Toggle({
    checked,
    onChange,
}: {
    checked: boolean;
    onChange: (value: boolean) => void;
}) {
    return (
        <button
            type="button"
            role="switch"
            aria-checked={checked}
            className={`toggle ${checked ? 'toggle--active' : ''}`}
            onClick={() => onChange(!checked)}
        >
            <span />
        </button>
    );
}
