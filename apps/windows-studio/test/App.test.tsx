import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '../src/renderer/App';

describe('App', () => {
    it('renders the app header with MAHANAIM CAM title', () => {
        render(<App />);
        expect(screen.getByText('MAHANAIM CAM')).toBeInTheDocument();
    });

    it('renders the dev device panel with empty state message', () => {
        render(<App />);
        const panel = screen.getByTestId('device-panel');
        expect(panel).toBeInTheDocument();
        expect(screen.getByText(/Waiting for Android connection/i)).toBeInTheDocument();
    });

    it('renders the status bar', () => {
        render(<App />);
        expect(screen.getByTestId('status-bar')).toBeInTheDocument();
    });

    it('shows the signaling port in the status bar', () => {
        render(<App />);
        expect(screen.getByText(/8765/)).toBeInTheDocument();
    });

    it('shows the OBS source port in the status bar', () => {
        render(<App />);
        expect(screen.getByText(/8766/)).toBeInTheDocument();
    });
});
