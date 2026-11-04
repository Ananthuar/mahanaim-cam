
import { WebSocketServer, WebSocket } from 'ws';
import { networkInterfaces } from 'node:os';
import { randomUUID } from 'node:crypto';

import {
    parseMessage,
    serializeMessage,
    type SignalingMessage,
} from '@mahanaim-cam/protocol';

const HOST = '0.0.0.0';

type SignalingMessageHandler = (message: SignalingMessage) => void;

// --------------------------------------------------
// NETWORK UTILITIES
// --------------------------------------------------

export function getLanIPv4Addresses(): string[] {
    const interfaces = networkInterfaces();
    const addresses = new Set<string>();

    for (const entries of Object.values(interfaces)) {
        for (const entry of entries ?? []) {
            if (
                entry.family === 'IPv4' &&
                !entry.internal &&
                isPrivateIPv4(entry.address)
            ) {
                addresses.add(entry.address);
            }
        }
    }

    return [...addresses].sort();
}

function isPrivateIPv4(address: string): boolean {
    const parts = address.split('.').map(Number);

    if (
        parts.length !== 4 ||
        parts.some(
            part =>
                !Number.isInteger(part) ||
                part < 0 ||
                part > 255
        )
    ) {
        return false;
    }

    const [a, b] = parts;

    return (
        a === 10 ||
        (a === 172 && b >= 16 && b <= 31) ||
        (a === 192 && b === 168)
    );
}

// --------------------------------------------------
// SIGNALING SERVER
// --------------------------------------------------

export class SignalingServer {
    private _wss: WebSocketServer | null = null;
    private readonly _port: number;

    private _connectionCount = 0;
    private _clientCounter = 0;

    private _pairedDeviceName: string | null = null;
    private _pairedSocket: WebSocket | null = null;
    private _sessionId: string | null = null;

    private _messageHandler: SignalingMessageHandler | null = null;

    constructor(port: number) {
        this._port = port;
    }

    setMessageHandler(handler: SignalingMessageHandler): void {
        this._messageHandler = handler;
    }

    // --------------------------------------------------
    // LOGGING
    // --------------------------------------------------

    private log(message: string): void {
        console.log(
            `[SignalingServer ${new Date().toISOString()}] ${message}`
        );
    }

    // --------------------------------------------------
    // SEND TO PAIRED ANDROID DEVICE
    // --------------------------------------------------

    sendToPaired(message: SignalingMessage): boolean {
        const socket = this._pairedSocket;

        if (!socket || socket.readyState !== WebSocket.OPEN) {
            this.log(
                `SEND FAILED: No paired Android device. Message: ${message.type}`
            );
            return false;
        }

        if (
            'sessionId' in message &&
            message.sessionId !== this._sessionId
        ) {
            this.log(
                `SEND REJECTED: Invalid session for ${message.type}`
            );
            return false;
        }

        try {
            socket.send(serializeMessage(message));

            this.log(
                `SENT TO ANDROID: ${message.type} | Session: ${
                    'sessionId' in message
                        ? message.sessionId
                        : 'N/A'
                }`
            );

            return true;
        } catch (error) {
            console.error(
                '[SignalingServer] Failed to send message:',
                error
            );

            return false;
        }
    }

    // --------------------------------------------------
    // START SERVER
    // --------------------------------------------------

    start(): Promise<void> {
        return new Promise((resolve, reject) => {
            if (this._wss) {
                resolve();
                return;
            }

            const server = new WebSocketServer({
                port: this._port,
                host: HOST,
            });

            this._wss = server;

            server.once('listening', () => {
                this.log(`Listening on ${HOST}:${this._port}`);

                const addresses = getLanIPv4Addresses();

                if (addresses.length === 0) {
                    this.log('WARNING: No private IPv4 address found.');
                } else {
                    this.log('Android connection addresses:');

                    for (const address of addresses) {
                        console.log(`  ws://${address}:${this._port}`);
                    }
                }

                resolve();
            });

            server.on('error', (err: Error) => {
                console.error(
                    '[SignalingServer] Server error:',
                    err.message
                );

                if (this._wss === server) {
                    this._wss = null;
                }

                reject(err);
            });

            // --------------------------------------------------
            // CLIENT CONNECTION
            // --------------------------------------------------

            server.on('connection', (ws: WebSocket, req) => {
                this._connectionCount++;
                this._clientCounter++;

                const clientId = this._clientCounter;
                const clientIp = req.socket.remoteAddress ?? 'unknown';

                this.log(
                    `CLIENT #${clientId} CONNECTED | IP: ${clientIp} | Active connections: ${this._connectionCount}`
                );

                ws.on('message', (data: Buffer) => {
                    const raw = data.toString('utf8');
                    const msg = parseMessage(raw);

                    if (!msg) {
                        this.log(
                            `CLIENT #${clientId}: INVALID MESSAGE RECEIVED`
                        );
                        return;
                    }

                    const messageSession =
                        'sessionId' in msg
                            ? msg.sessionId
                            : 'N/A';

                    this.log(
                        `CLIENT #${clientId} RECEIVED: ${msg.type} | Session: ${messageSession}`
                    );

                    // --------------------------------------------------
                    // PAIR REQUEST
                    // --------------------------------------------------

                    switch (msg.type) {
                        case 'PAIR_REQUEST': {
                            if (
                                typeof msg.deviceName !== 'string' ||
                                msg.deviceName.length < 1 ||
                                msg.deviceName.length > 100 ||
                                typeof msg.uuid !== 'string' ||
                                msg.uuid.length < 1 ||
                                msg.uuid.length > 100 ||
                                typeof msg.platformVersion !== 'string' ||
                                msg.platformVersion.length > 30
                            ) {
                                this.log(
                                    `CLIENT #${clientId}: PAIRING REJECTED - Invalid pairing information`
                                );

                                ws.send(
                                    serializeMessage({
                                        type: 'PAIR_REJECT',
                                        reason: 'Invalid pairing information.',
                                    })
                                );

                                return;
                            }

                            if (
                                this._pairedSocket &&
                                this._pairedSocket !== ws &&
                                this._pairedSocket.readyState === WebSocket.OPEN
                            ) {
                                this.log(
                                    `CLIENT #${clientId}: PAIRING REJECTED - Another device is already paired`
                                );

                                ws.send(
                                    serializeMessage({
                                        type: 'PAIR_REJECT',
                                        reason: 'Another device is already paired.',
                                    })
                                );

                                return;
                            }

                            const sessionId = randomUUID();

                            this._pairedSocket = ws;
                            this._pairedDeviceName = msg.deviceName;
                            this._sessionId = sessionId;

                            this.log(
                                `CLIENT #${clientId}: PAIRING ACCEPTED | Device: ${msg.deviceName} | Session: ${sessionId}`
                            );

                            // Confirm pairing to Android.
                            ws.send(
                                serializeMessage({
                                    type: 'PAIR_ACCEPT',
                                    sessionId,
                                })
                            );

                            this.log(
                                `CLIENT #${clientId}: PAIR_ACCEPT SENT`
                            );

                            // Notify desktop renderer.
                            this._messageHandler?.(msg);

                            this._messageHandler?.({
                                type: 'PAIR_ACCEPT',
                                sessionId,
                            });

                            break;
                        }

                        // --------------------------------------------------
                        // HEARTBEAT
                        // --------------------------------------------------

                        case 'HEARTBEAT': {
                            if (typeof msg.timestamp === 'number') {
                                ws.send(
                                    serializeMessage({
                                        type: 'HEARTBEAT_ACK',
                                        timestamp: msg.timestamp,
                                    })
                                );
                            }

                            break;
                        }

                        // --------------------------------------------------
                        // ANDROID WEBRTC MESSAGES
                        // --------------------------------------------------

                        case 'SDP_ANSWER':
                        case 'ICE_CANDIDATE':
                        case 'SESSION_END': {
                            if (ws !== this._pairedSocket) {
                                this.log(
                                    `CLIENT #${clientId}: ${msg.type} REJECTED - Client is not paired`
                                );
                                return;
                            }

                            if (
                                !this._sessionId ||
                                msg.sessionId !== this._sessionId
                            ) {
                                this.log(
                                    `CLIENT #${clientId}: ${msg.type} REJECTED - Invalid session`
                                );
                                return;
                            }

                            this.log(
                                `CLIENT #${clientId}: FORWARDING ${msg.type} TO DESKTOP | Session: ${msg.sessionId}`
                            );

                            this._messageHandler?.(msg);

                            break;
                        }

                        // --------------------------------------------------
                        // UNHANDLED MESSAGES
                        // --------------------------------------------------

                        default: {
                            this.log(
                                `CLIENT #${clientId}: NO HANDLER FOR ${msg.type}`
                            );
                        }
                    }
                });

                // --------------------------------------------------
                // CLIENT DISCONNECTION
                // --------------------------------------------------

                ws.on('close', (code, reason) => {
                    this._connectionCount = Math.max(
                        0,
                        this._connectionCount - 1
                    );

                    const closeReason = reason.toString('utf8') || '(empty)';

                    this.log(
                        `CLIENT #${clientId} CLOSED | Code: ${code} | Reason: ${closeReason} | Remaining connections: ${this._connectionCount}`
                    );

                    if (this._pairedSocket === ws) {
                        const endedSessionId = this._sessionId;

                        this.log(
                            `PAIRED DEVICE DISCONNECTED | Device: ${this._pairedDeviceName} | Session: ${endedSessionId}`
                        );

                        this._pairedSocket = null;
                        this._pairedDeviceName = null;
                        this._sessionId = null;

                        if (endedSessionId) {
                            this.log(
                                `NOTIFYING DESKTOP: SESSION_END | Session: ${endedSessionId}`
                            );

                            this._messageHandler?.({
                                type: 'SESSION_END',
                                sessionId: endedSessionId,
                            });
                        }
                    }
                });

                ws.on('error', (err: Error) => {
                    console.error(
                        `[SignalingServer] CLIENT #${clientId} WebSocket error:`,
                        err.message
                    );
                });
            });
        });
    }

    // --------------------------------------------------
    // STOP SERVER
    // --------------------------------------------------

    stop(): Promise<void> {
        return new Promise(resolve => {
            if (!this._wss) {
                resolve();
                return;
            }

            const server = this._wss;

            this.log('Stopping signaling server...');

            this._wss = null;

            server.close(() => {
                this.log('Signaling server stopped');

                this._pairedSocket = null;
                this._pairedDeviceName = null;
                this._sessionId = null;
                this._connectionCount = 0;

                resolve();
            });
        });
    }

    // --------------------------------------------------
    // GETTERS
    // --------------------------------------------------

    get connectionCount(): number {
        return this._connectionCount;
    }

    get pairedDeviceName(): string | null {
        return this._pairedDeviceName;
    }

    get sessionId(): string | null {
        return this._sessionId;
    }

    get isPaired(): boolean {
        return (
            this._pairedSocket !== null &&
            this._pairedSocket.readyState === WebSocket.OPEN
        );
    }

    get isRunning(): boolean {
        return this._wss !== null && this._wss.address() !== null;
    }
}
