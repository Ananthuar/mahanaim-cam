
import { WebSocketServer, WebSocket } from 'ws';
import { networkInterfaces } from 'node:os';
import { randomUUID } from 'node:crypto';
import { parseMessage, serializeMessage } from '@mahanaim-cam/protocol';

const HOST = '0.0.0.0';

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
        parts.some(part =>
            !Number.isInteger(part) || part < 0 || part > 255
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

export class SignalingServer {
    private _wss: WebSocketServer | null = null;
    private readonly _port: number;
    private _connectionCount = 0;
    private _pairedDeviceName: string | null = null;
    private _pairedSocket: WebSocket | null = null;

    constructor(port: number) {
        this._port = port;
    }

    start(): Promise<void> {
        return new Promise((resolve, reject) => {
            const server = new WebSocketServer({
                port: this._port,
                host: HOST,
            });

            this._wss = server;

            server.once('listening', () => {
                console.log(
                    `[SignalingServer] Listening on ${HOST}:${this._port}`
                );

                const addresses = getLanIPv4Addresses();

                if (addresses.length === 0) {
                    console.warn(
                        '[SignalingServer] No private IPv4 address found.'
                    );
                } else {
                    console.log(
                        '[SignalingServer] Android connection addresses:'
                    );

                    for (const address of addresses) {
                        console.log(`  ws://${address}:${this._port}`);
                    }
                }

                resolve();
            });

            server.on('error', (err: Error) => {
                console.error('[SignalingServer] Server error:', err.message);

                if (this._wss === server) {
                    this._wss = null;
                }

                reject(err);
            });

            server.on('connection', (ws: WebSocket, req) => {
                this._connectionCount++;

                const clientIp = req.socket.remoteAddress ?? 'unknown';

                console.log(
                    `[SignalingServer] Client connected from ${clientIp}`
                );

                ws.on('message', (data: Buffer) => {
                    const raw = data.toString('utf8');
                    const msg = parseMessage(raw);

                    if (!msg) {
                        console.warn('[SignalingServer] Invalid message');
                        return;
                    }

                    console.log(`[SignalingServer] Received: ${msg.type}`);

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
                                ws.send(serializeMessage({
                                    type: 'PAIR_REJECT',
                                    reason: 'Invalid pairing information.',
                                }));
                                return;
                            }

                            if (
                                this._pairedSocket &&
                                this._pairedSocket !== ws &&
                                this._pairedSocket.readyState === WebSocket.OPEN
                            ) {
                                ws.send(serializeMessage({
                                    type: 'PAIR_REJECT',
                                    reason: 'Another device is already paired.',
                                }));
                                return;
                            }

                            this._pairedSocket = ws;
                            this._pairedDeviceName = msg.deviceName;

                            const sessionId = randomUUID();

                            console.log(
                                `[SignalingServer] Paired device: ${msg.deviceName}`
                            );

                            ws.send(serializeMessage({
                                type: 'PAIR_ACCEPT',
                                sessionId,
                            }));

                            break;
                        }

                        case 'HEARTBEAT': {
                            if (typeof msg.timestamp === 'number') {
                                ws.send(serializeMessage({
                                    type: 'HEARTBEAT_ACK',
                                    timestamp: msg.timestamp,
                                }));
                            }
                            break;
                        }

                        default:
                            console.log(
                                `[SignalingServer] No handler yet for: ${msg.type}`
                            );
                    }
                });

                ws.on('close', () => {
                    this._connectionCount = Math.max(
                        0,
                        this._connectionCount - 1
                    );

                    if (this._pairedSocket === ws) {
                        console.log(
                            `[SignalingServer] Paired device disconnected: ${this._pairedDeviceName}`
                        );

                        this._pairedSocket = null;
                        this._pairedDeviceName = null;
                    }

                    console.log(
                        `[SignalingServer] Client disconnected (remaining: ${this._connectionCount})`
                    );
                });

                ws.on('error', (err: Error) => {
                    console.error(
                        '[SignalingServer] WebSocket error:',
                        err.message
                    );
                });
            });
        });
    }

    stop(): Promise<void> {
        return new Promise(resolve => {
            if (!this._wss) {
                resolve();
                return;
            }

            this._wss.close(() => {
                console.log('[SignalingServer] Stopped');
                this._wss = null;
                this._pairedSocket = null;
                this._pairedDeviceName = null;
                this._connectionCount = 0;
                resolve();
            });
        });
    }

    get connectionCount(): number {
        return this._connectionCount;
    }

    get pairedDeviceName(): string | null {
        return this._pairedDeviceName;
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
