
import { contextBridge, ipcRenderer } from 'electron';

import type { SignalingMessage } from '@mahanaim-cam/protocol';

export interface DesktopConnectionInfo {
    signalingPort: number;
    obsSourcePort: number;
    lanAddresses: string[];
    signalingRunning: boolean;
}

contextBridge.exposeInMainWorld('mahanaim', {
    // Get desktop connection information
    getConnectionInfo: (): Promise<DesktopConnectionInfo> => {
        return ipcRenderer.invoke('connection:get-info');
    },

    // Send a signaling message to Android
    sendSignalingMessage: (
        message: SignalingMessage
    ): Promise<boolean> => {
        return ipcRenderer.invoke('signaling:send', message);
    },

    // Listen for signaling messages received from Android
    onSignalingMessage: (
        callback: (message: SignalingMessage) => void
    ): (() => void) => {
        const listener = (
            _event: Electron.IpcRendererEvent,
            message: SignalingMessage
        ) => {
            callback(message);
        };

        ipcRenderer.on('signaling:message', listener);

        // Return cleanup function
        return () => {
            ipcRenderer.removeListener('signaling:message', listener);
        };
    },
});
