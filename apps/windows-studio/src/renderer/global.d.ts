
import type { SignalingMessage } from '@mahanaim-cam/protocol';

declare global {
    interface DesktopConnectionInfo {
        signalingPort: number;
        obsSourcePort: number;
        lanAddresses: string[];
        signalingRunning: boolean;
    }

    interface Window {
        mahanaim: {
            // Get desktop connection information
            getConnectionInfo: () => Promise<DesktopConnectionInfo>;

            // Send a message to Android
            sendSignalingMessage: (
                message: SignalingMessage
            ) => Promise<boolean>;

            // Receive messages from Android
            onSignalingMessage: (
                callback: (message: SignalingMessage) => void
            ) => () => void;
        };
    }
}

export {};
