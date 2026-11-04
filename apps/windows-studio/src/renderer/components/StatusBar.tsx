import React from 'react';

interface StatusBarProps {
    signalingPort: number;
    obsSourcePort: number;
    signalingRunning: boolean;
    connectedDevices: number;
}

export default function StatusBar({
    signalingPort,
    obsSourcePort,
    signalingRunning,
    connectedDevices,
}: StatusBarProps) {
    return (
        <footer className="status-bar" data-testid="status-bar">
            {/* Signaling server status */}
            <div className="status-bar__item">
                <span
                    className={`status-indicator ${signalingRunning ? 'status-indicator--online' : 'status-indicator--offline'}`}
                    aria-label={signalingRunning ? 'Signaling server online' : 'Signaling server offline'}
                />
                <span>Signaling ws://localhost:{signalingPort}</span>
            </div>

            {/* OBS source status */}
            <div className="status-bar__item">
                <span className="status-indicator status-indicator--online" />
                <span>OBS Source http://localhost:{obsSourcePort}</span>
            </div>

            {/* Connected devices */}
            <div className="status-bar__item">
                <span>
                    {connectedDevices === 0
                        ? 'No devices connected'
                        : `${connectedDevices} device${connectedDevices > 1 ? 's' : ''} connected`}
                </span>
            </div>
        </footer>
    );
}
