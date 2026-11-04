import React from 'react';

interface DevicePanelProps {
    connectedDevices: number;
}

export default function DevicePanel({ connectedDevices }: DevicePanelProps) {
    return (
        <>
            <div className="sidebar__section">
                <div className="sidebar__label">Devices</div>
            </div>

            <div className="sidebar__content" data-testid="device-panel">
                {connectedDevices === 0 ? (
                    <div className="empty-state">
                        <div className="empty-state__icon">📱</div>
                        <div className="empty-state__text">
                            Waiting for Android connection…
                            <br />
                            <br />
                            Open MAHANAIM CAM on your phone and tap <strong>Connect</strong>.
                        </div>
                    </div>
                ) : (
                    // TODO(milestone-2): Render actual connected device cards from IPC state
                    <div className="empty-state">
                        <div className="empty-state__text">{connectedDevices} device(s) ready</div>
                    </div>
                )}
            </div>
        </>
    );
}
