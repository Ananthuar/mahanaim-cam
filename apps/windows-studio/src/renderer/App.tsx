
import React, { useState, useEffect, useRef } from 'react';
import { QRCodeSVG } from 'qrcode.react';

import StatusBar from './components/StatusBar';
import DevicePanel from './components/DevicePanel';

import type { SignalingMessage } from '@mahanaim-cam/protocol';

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
    const [pairedDevice, setPairedDevice] = useState('');
    const [streamStatus, setStreamStatus] = useState(
        'Waiting for Android connection'
    );

    const videoRef = useRef<HTMLVideoElement>(null);
    const peerConnectionRef = useRef<RTCPeerConnection | null>(null);
    const sessionIdRef = useRef<string | null>(null);

    const pendingIceCandidatesRef = useRef<RTCIceCandidateInit[]>([]);

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

                console.error(
                    '[Renderer] Failed to retrieve connection information:',
                    error
                );

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
    // CLEAN UP WEBRTC
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

        if (videoRef.current) {
            videoRef.current.srcObject = null;
        }
    }

    // --------------------------------------------------
    // START WEBRTC CONNECTION
    // --------------------------------------------------

    async function startWebRTC(sessionId: string) {
        try {
            console.log('[WebRTC] Starting session:', sessionId);

            // Close any previous connection.
            closeWebRTC();

            sessionIdRef.current = sessionId;

            const peerConnection = new RTCPeerConnection({
                iceServers: [],
            });

            peerConnectionRef.current = peerConnection;

            peerConnection.ontrack = event => {
                console.log('[WebRTC] Remote video track received');

                const stream = event.streams[0];

                if (stream) {
                    if (videoRef.current) {
                        videoRef.current.srcObject = stream;
                    }
                } else {
                    const streamWithoutId = new MediaStream([event.track]);

                    if (videoRef.current) {
                        videoRef.current.srcObject = streamWithoutId;
                    }
                }

                setStreamStatus('Receiving camera stream');
            };

            // Send desktop ICE candidates to Android.
            peerConnection.onicecandidate = event => {
                if (!event.candidate) return;

                const activeSessionId = sessionIdRef.current;

                if (activeSessionId !== sessionId) return;

                const message: SignalingMessage = {
                    type: 'ICE_CANDIDATE',
                    sessionId,
                    candidate: event.candidate.candidate,
                    sdpMid: event.candidate.sdpMid ?? '',
                    sdpMLineIndex: event.candidate.sdpMLineIndex ?? 0,
                };

                void window.mahanaim
                    .sendSignalingMessage(message)
                    .then(sent => {
                        if (!sent) {
                            console.warn(
                                '[WebRTC] Failed to send ICE candidate'
                            );
                        }
                    })
                    .catch(error => {
                        console.error(
                            '[WebRTC] ICE candidate sending failed:',
                            error
                        );
                    });
            };

            // Monitor the peer connection.
            peerConnection.onconnectionstatechange = () => {
                const state = peerConnection.connectionState;

                console.log('[WebRTC] Connection state:', state);

                if (state === 'connected') {
                    setStreamStatus('Video connection established');
                } else if (state === 'connecting') {
                    setStreamStatus('Establishing video connection...');
                } else if (state === 'disconnected') {
                    setStreamStatus('Connection interrupted');
                } else if (state === 'failed') {
                    setStreamStatus('Video connection failed');
                } else if (state === 'closed') {
                    setStreamStatus('Video connection closed');
                }
            };

            // Desktop receives video from Android.
            peerConnection.addTransceiver('video', {
                direction: 'recvonly',
            });

            // Create and send the SDP offer.
            const offer = await peerConnection.createOffer();

            await peerConnection.setLocalDescription(offer);

            if (sessionIdRef.current !== sessionId) {
                return;
            }

            const message: SignalingMessage = {
                type: 'SDP_OFFER',
                sessionId,
                sdp: offer.sdp ?? '',
            };

            const sent = await window.mahanaim.sendSignalingMessage(message);

            if (!sent) {
                throw new Error(
                    'Unable to send WebRTC offer to Android.'
                );
            }

            setStreamStatus('Waiting for Android video response...');

            console.log('[WebRTC] SDP offer sent');
        } catch (error) {
            console.error(
                '[WebRTC] Failed to start connection:',
                error
            );

            setStreamStatus('Unable to start video connection');
        }
    }

    // --------------------------------------------------
    // HANDLE ANDROID SIGNALING MESSAGES
    // --------------------------------------------------

    useEffect(() => {
        const unsubscribe = window.mahanaim.onSignalingMessage(
            async (message: SignalingMessage) => {
                console.log(
                    '[Renderer] Signaling message:',
                    message.type
                );

                switch (message.type) {
                    // ------------------------------------------
                    // ANDROID PAIR REQUEST
                    // ------------------------------------------

                    case 'PAIR_REQUEST': {
                        setPairedDevice(message.deviceName);

                        setAppState(prev => ({
                            ...prev,
                            connectedDevices: 1,
                        }));

                        setStreamStatus(
                            'Android paired. Waiting for session confirmation...'
                        );

                        break;
                    }

                    // ------------------------------------------
                    // PAIR ACCEPTED - START WEBRTC
                    // ------------------------------------------

                    case 'PAIR_ACCEPT': {
                        setStreamStatus(
                            'Pairing successful. Starting WebRTC...'
                        );

                        await startWebRTC(message.sessionId);

                        break;
                    }

                    // ------------------------------------------
                    // RECEIVE SDP ANSWER
                    // ------------------------------------------

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

                            console.log(
                                '[WebRTC] Remote SDP answer applied'
                            );

                            // Apply ICE candidates received before the answer.
                            const pendingCandidates =
                                pendingIceCandidatesRef.current;

                            pendingIceCandidatesRef.current = [];

                            for (const candidate of pendingCandidates) {
                                await peerConnection.addIceCandidate(candidate);
                            }

                            setStreamStatus(
                                'WebRTC answer received. Connecting...'
                            );
                        } catch (error) {
                            console.error(
                                '[WebRTC] Failed to apply SDP answer:',
                                error
                            );

                            setStreamStatus(
                                'Failed to process Android video response'
                            );
                        }

                        break;
                    }

                    // ------------------------------------------
                    // RECEIVE ANDROID ICE CANDIDATES
                    // ------------------------------------------

                    case 'ICE_CANDIDATE': {
                        if (
                            message.sessionId !== sessionIdRef.current
                        ) {
                            return;
                        }

                        const candidate: RTCIceCandidateInit = {
                            candidate: message.candidate,
                            sdpMid: message.sdpMid,
                            sdpMLineIndex: message.sdpMLineIndex,
                        };

                        const peerConnection = peerConnectionRef.current;

                        if (
                            !peerConnection ||
                            !peerConnection.remoteDescription
                        ) {
                            pendingIceCandidatesRef.current.push(candidate);
                            break;
                        }

                        try {
                            await peerConnection.addIceCandidate(candidate);

                            console.log(
                                '[WebRTC] Android ICE candidate added'
                            );
                        } catch (error) {
                            console.error(
                                '[WebRTC] Failed to add Android ICE candidate:',
                                error
                            );
                        }

                        break;
                    }

                    // ------------------------------------------
                    // SESSION ENDED
                    // ------------------------------------------

                    case 'SESSION_END': {
                        if (
                            message.sessionId !== sessionIdRef.current
                        ) {
                            return;
                        }

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

        return () => {
            unsubscribe();
        };
    }, []);

    // --------------------------------------------------
    // COMPONENT CLEANUP
    // --------------------------------------------------

    useEffect(() => {
        return () => {
            closeWebRTC();
        };
    }, []);

    // --------------------------------------------------
    // QR CODE AND CONNECTION ADDRESS
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

            window.setTimeout(() => {
                setCopied(false);
            }, 2000);
        } catch (error) {
            console.error(
                '[Renderer] Clipboard copy failed:',
                error
            );
        }
    }

    // --------------------------------------------------
    // USER INTERFACE
    // --------------------------------------------------

    return (
        <div className="app-layout">
            <header className="app-header">
                <div className="app-header__logo">
                    <div className="app-header__logo-icon">📷</div>

                    <span className="app-header__logo-text">
                        MAHANAIM CAM
                    </span>

                    <span className="app-header__logo-version">
                        v0.1.0
                    </span>
                </div>
            </header>

            <main className="app-main">
                <aside className="sidebar">
                    <DevicePanel
                        connectedDevices={appState.connectedDevices}
                    />
                </aside>

                <section className="content-area">
                    <div className="stream-view">

                        {/* LIVE VIDEO DISPLAY */}

                        <video
                            ref={videoRef}
                            autoPlay
                            playsInline
                            muted
                            style={{
                                width: '100%',
                                height: '100%',
                                objectFit: 'contain',
                                background: '#080a0f',
                                display: pairedDevice ? 'block' : 'none',
                            }}
                        />

                        {/* CONNECTION PLACEHOLDER */}

                        {!pairedDevice && (
                            <div className="stream-view__placeholder">
                                <div className="stream-view__icon">
                                    📡
                                </div>

                                <div className="stream-view__title">
                                    Connect Your Android Phone
                                </div>

                                <div className="stream-view__subtitle">
                                    Scan this QR code using MAHANAIM CAM on
                                    your Android phone. Make sure both
                                    devices are connected to the same network.
                                </div>

                                {appState.loading ? (
                                    <p>
                                        Loading connection information...
                                    </p>
                                ) : appState.error ? (
                                    <p>{appState.error}</p>
                                ) : (
                                    <div style={{ marginTop: '24px' }}>
                                        <p>
                                            Desktop Server:{' '}
                                            {appState.signalingRunning
                                                ? 'Running'
                                                : 'Offline'}
                                        </p>

                                        {appState.signalingRunning &&
                                        selectedAddress ? (
                                            <>
                                                {appState.lanAddresses.length >
                                                    1 && (
                                                    <div
                                                        style={{
                                                            margin: '16px 0',
                                                        }}
                                                    >
                                                        <label htmlFor="network-address">
                                                            Select network:
                                                        </label>

                                                        <br />

                                                        <select
                                                            id="network-address"
                                                            value={selectedAddress}
                                                            onChange={event =>
                                                                setSelectedAddress(
                                                                    event.target.value
                                                                )
                                                            }
                                                            style={{
                                                                marginTop: '8px',
                                                                padding: '8px',
                                                            }}
                                                        >
                                                            {appState.lanAddresses.map(
                                                                address => (
                                                                    <option
                                                                        key={address}
                                                                        value={address}
                                                                    >
                                                                        {address}
                                                                    </option>
                                                                )
                                                            )}
                                                        </select>
                                                    </div>
                                                )}

                                                <div
                                                    style={{
                                                        display: 'flex',
                                                        flexDirection: 'column',
                                                        alignItems: 'center',
                                                        gap: '12px',
                                                        marginTop: '20px',
                                                    }}
                                                >
                                                    <div
                                                        style={{
                                                            background: '#ffffff',
                                                            padding: '16px',
                                                            borderRadius: '12px',
                                                        }}
                                                    >
                                                        <QRCodeSVG
                                                            value={pairingPayload}
                                                            size={220}
                                                            level="M"
                                                            includeMargin
                                                        />
                                                    </div>

                                                    <p
                                                        style={{
                                                            fontWeight: 600,
                                                        }}
                                                    >
                                                        {connectionUrl}
                                                    </p>

                                                    <button
                                                        onClick={
                                                            copyConnectionAddress
                                                        }
                                                        style={{
                                                            padding: '10px 18px',
                                                            borderRadius: '8px',
                                                            border: 'none',
                                                            cursor: 'pointer',
                                                        }}
                                                    >
                                                        {copied
                                                            ? 'Copied!'
                                                            : 'Copy Connection Address'}
                                                    </button>

                                                    <p
                                                        style={{
                                                            fontSize: '13px',
                                                            opacity: 0.7,
                                                        }}
                                                    >
                                                        WebSocket Port:{' '}
                                                        {appState.signalingPort}
                                                    </p>
                                                </div>
                                            </>
                                        ) : (
                                            <p>
                                                No active LAN connection
                                                detected. Check your network
                                                connection and restart the
                                                desktop app.
                                            </p>
                                        )}
                                    </div>
                                )}
                            </div>
                        )}

                        {/* STREAM STATUS */}

                        {pairedDevice && (
                            <div
                                style={{
                                    position: 'absolute',
                                    top: '16px',
                                    left: '16px',
                                    zIndex: 2,
                                    padding: '10px 14px',
                                    borderRadius: '8px',
                                    background: 'rgba(0,0,0,0.7)',
                                    color: '#ffffff',
                                }}
                            >
                                <strong>{pairedDevice}</strong>

                                <div
                                    style={{
                                        fontSize: '12px',
                                        marginTop: '4px',
                                    }}
                                >
                                    {streamStatus}
                                </div>
                            </div>
                        )}
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
