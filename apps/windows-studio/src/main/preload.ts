
import { contextBridge, ipcRenderer } from 'electron';

export interface DesktopConnectionInfo {
    signalingPort: number;
    obsSourcePort: number;
    lanAddresses: string[];
    signalingRunning: boolean;
}

contextBridge.exposeInMainWorld('mahanaim', {
    getConnectionInfo: (): Promise<DesktopConnectionInfo> =>
        ipcRenderer.invoke('connection:get-info'),
});
