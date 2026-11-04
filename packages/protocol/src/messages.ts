/**
 * MAHANAIM CAM — WebSocket Signaling Protocol Messages
 *
 * All messages exchanged between the Android app and Windows Studio
 * signaling server are JSON-serialized forms of these types.
 *
 * Version: 0.1.0
 */

// ---------------------------------------------------------------------------
// Base
// ---------------------------------------------------------------------------

export interface BaseMessage {
    type: MessageType;
}

export type MessageType =
    | 'PAIR_REQUEST'
    | 'PAIR_ACCEPT'
    | 'PAIR_REJECT'
    | 'SESSION_START'
    | 'SESSION_END'
    | 'SDP_OFFER'
    | 'SDP_ANSWER'
    | 'ICE_CANDIDATE'
    | 'HEARTBEAT'
    | 'HEARTBEAT_ACK'
    | 'ERROR';

// ---------------------------------------------------------------------------
// Pairing
// ---------------------------------------------------------------------------

/** Android → Windows: request to pair this device */
export interface PairRequestMessage extends BaseMessage {
    type: 'PAIR_REQUEST';
    /** Human-readable device name (e.g. "Ananthu's Pixel 7") */
    deviceName: string;
    /** Stable device UUID (generated once, stored in SharedPreferences) */
    uuid: string;
    /** Android API level string (e.g. "34") */
    platformVersion: string;
}

/** Windows → Android: pairing accepted */
export interface PairAcceptMessage extends BaseMessage {
    type: 'PAIR_ACCEPT';
    /** Unique session identifier (UUIDv4) */
    sessionId: string;
}

/** Windows → Android: pairing rejected */
export interface PairRejectMessage extends BaseMessage {
    type: 'PAIR_REJECT';
    reason: string;
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

/** Android → Windows: begin streaming session */
export interface SessionStartMessage extends BaseMessage {
    type: 'SESSION_START';
    sessionId: string;
}

/** Either direction: end the streaming session */
export interface SessionEndMessage extends BaseMessage {
    type: 'SESSION_END';
    sessionId: string;
    reason?: string;
}

// ---------------------------------------------------------------------------
// WebRTC SDP
// ---------------------------------------------------------------------------

/** Windows → Android: WebRTC offer */
export interface SdpOfferMessage extends BaseMessage {
    type: 'SDP_OFFER';
    sessionId: string;
    /** Full SDP offer string */
    sdp: string;
}

/** Android → Windows: WebRTC answer */
export interface SdpAnswerMessage extends BaseMessage {
    type: 'SDP_ANSWER';
    sessionId: string;
    /** Full SDP answer string */
    sdp: string;
}

// ---------------------------------------------------------------------------
// ICE
// ---------------------------------------------------------------------------

/** Either direction: trickle ICE candidate */
export interface IceCandidateMessage extends BaseMessage {
    type: 'ICE_CANDIDATE';
    sessionId: string;
    /** ICE candidate string */
    candidate: string;
    sdpMid: string | null;
    sdpMLineIndex: number | null;
}

// ---------------------------------------------------------------------------
// Keep-alive
// ---------------------------------------------------------------------------

/** Either direction: heartbeat */
export interface HeartbeatMessage extends BaseMessage {
    type: 'HEARTBEAT';
    /** Unix ms timestamp */
    timestamp: number;
}

/** Either direction: heartbeat acknowledgment */
export interface HeartbeatAckMessage extends BaseMessage {
    type: 'HEARTBEAT_ACK';
    /** Echo of the original timestamp */
    timestamp: number;
}

// ---------------------------------------------------------------------------
// Error
// ---------------------------------------------------------------------------

/** Either direction: error notification */
export interface ErrorMessage extends BaseMessage {
    type: 'ERROR';
    code: ErrorCode;
    message: string;
    sessionId?: string;
}

export type ErrorCode =
    | 'PAIR_FAILED'
    | 'SESSION_LIMIT_REACHED'
    | 'SDP_INVALID'
    | 'ICE_FAILED'
    | 'UNKNOWN';

// ---------------------------------------------------------------------------
// Union
// ---------------------------------------------------------------------------

/** Discriminated union of all possible signaling messages */
export type SignalingMessage =
    | PairRequestMessage
    | PairAcceptMessage
    | PairRejectMessage
    | SessionStartMessage
    | SessionEndMessage
    | SdpOfferMessage
    | SdpAnswerMessage
    | IceCandidateMessage
    | HeartbeatMessage
    | HeartbeatAckMessage
    | ErrorMessage;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Type-safe parse of a raw WebSocket message string.
 * Returns null if the message is not a valid SignalingMessage.
 */
export function parseMessage(raw: string): SignalingMessage | null {
    try {
        const parsed = JSON.parse(raw) as unknown;
        if (
            typeof parsed === 'object' &&
            parsed !== null &&
            'type' in parsed &&
            typeof (parsed as Record<string, unknown>).type === 'string'
        ) {
            return parsed as SignalingMessage;
        }
        return null;
    } catch {
        return null;
    }
}

/**
 * Serialize a SignalingMessage to a JSON string for WebSocket transmission.
 */
export function serializeMessage(msg: SignalingMessage): string {
    return JSON.stringify(msg);
}
