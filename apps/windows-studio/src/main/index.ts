
import { app, BrowserWindow, shell, ipcMain } from 'electron';
import * as path from 'path';

import {
    SignalingServer,
    getLanIPv4Addresses,
} from './signaling/server';

import { ObsSourceServer } from './obs-source/server';

import type { SignalingMessage } from '@mahanaim-cam/protocol';

// Server configuration
const SIGNALING_PORT = 8765;
const OBS_SOURCE_PORT = 8766;

const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;

// Application references
let mainWindow: BrowserWindow | null = null;

const signalingServer = new SignalingServer(SIGNALING_PORT);
const obsServer = new ObsSourceServer(OBS_SOURCE_PORT);

// --------------------------------------------------
// ANDROID MESSAGE FORWARDING
// --------------------------------------------------

signalingServer.setMessageHandler((message) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('signaling:message', message);
    }
});

// --------------------------------------------------
// IPC HANDLERS
// --------------------------------------------------

// Get desktop connection information
ipcMain.handle('connection:get-info', () => {
    return {
        signalingPort: SIGNALING_PORT,
        obsSourcePort: OBS_SOURCE_PORT,
        lanAddresses: getLanIPv4Addresses(),
        signalingRunning: signalingServer.isRunning,
    };
});

// Send signaling messages from renderer to Android
ipcMain.handle(
    'signaling:send',
    (_event, message: SignalingMessage) => {
        const allowedTypes = [
            'SESSION_START',
            'SESSION_END',
            'SDP_OFFER',
            'ICE_CANDIDATE',
        ];

        if (
            !message ||
            typeof message !== 'object' ||
            !allowedTypes.includes(message.type)
        ) {
            return false;
        }

        return signalingServer.sendToPaired(message);
    },
);

// --------------------------------------------------
// CREATE MAIN WINDOW
// --------------------------------------------------

function createWindow(): void {
    mainWindow = new BrowserWindow({
        width: 1280,
        height: 800,
        minWidth: 900,
        minHeight: 600,
        backgroundColor: '#0d0f14',

        titleBarStyle: 'hidden',

        titleBarOverlay: {
            color: '#13161e',
            symbolColor: '#8b93a8',
            height: 52,
        },

        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
        },

        show: false,
    });

    // Load application
    if (isDev) {
        mainWindow.loadURL('http://localhost:5173');

        mainWindow.webContents.openDevTools({
            mode: 'detach',
        });
    } else {
        mainWindow.loadFile(
            path.join(__dirname, '../renderer/index.html')
        );
    }

    // Show window when ready
    mainWindow.once('ready-to-show', () => {
        mainWindow?.show();
    });

    // Handle external links securely
    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
        shell.openExternal(url);
        return { action: 'deny' };
    });

    // Clear window reference
    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}

// --------------------------------------------------
// APPLICATION STARTUP
// --------------------------------------------------

app.whenReady().then(async () => {
    console.log('[Main] Electron app ready');

    // Start signaling server
    try {
        await signalingServer.start();

        console.log(
            `[Main] Signaling server started on ws://localhost:${SIGNALING_PORT}`
        );

        const addresses = getLanIPv4Addresses();

        addresses.forEach((address) => {
            console.log(
                `[Main] Android connection: ws://${address}:${SIGNALING_PORT}`
            );
        });
    } catch (err) {
        console.error(
            '[Main] Failed to start signaling server:',
            err
        );
    }

    // Start OBS source server
    try {
        await obsServer.start();

        console.log(
            `[Main] OBS Source server started on http://localhost:${OBS_SOURCE_PORT}`
        );
    } catch (err) {
        console.error(
            '[Main] Failed to start OBS source server:',
            err
        );
    }

    // Create application window
    createWindow();

    // macOS window activation support
    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) {
            createWindow();
        }
    });
});

// --------------------------------------------------
// APPLICATION SHUTDOWN
// --------------------------------------------------

app.on('window-all-closed', async () => {
    console.log('[Main] All windows closed — shutting down servers');

    await signalingServer.stop();
    await obsServer.stop();

    if (process.platform !== 'darwin') {
        app.quit();
    }
});

// --------------------------------------------------
// SECURITY: NAVIGATION PROTECTION
// --------------------------------------------------

app.on('web-contents-created', (_event, contents) => {
    contents.on('will-navigate', (event, url) => {
        if (
            !isDev ||
            !url.startsWith('http://localhost:5173')
        ) {
            event.preventDefault();
        }
    });
});
