import * as http from 'http';

const OBS_SOURCE_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>MAHANAIM CAM — OBS Source</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      width: 1280px; height: 720px;
      background: #0d0f14;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      font-family: -apple-system, 'Segoe UI', sans-serif;
      color: #8b93a8;
      overflow: hidden;
    }
    .icon { font-size: 64px; margin-bottom: 16px; opacity: 0.3; }
    .title { font-size: 18px; font-weight: 600; color: #f0f2f7; margin-bottom: 8px; }
    .subtitle { font-size: 13px; opacity: 0.7; }
    #video-container {
      position: absolute;
      top: 0; left: 0;
      width: 100%; height: 100%;
      display: none;
    }
    #video-container video {
      width: 100%; height: 100%;
      object-fit: contain;
      background: #000;
    }
    .placeholder { text-align: center; }
  </style>
</head>
<body>
  <!-- TODO(milestone-3): Replace this placeholder with WebRTC video element.
       The video element will receive the stream from the Electron peer connection. -->
  <div id="video-container">
    <video id="remote-video" autoplay playsinline muted></video>
  </div>
  <div class="placeholder">
    <div class="icon">📡</div>
    <div class="title">MAHANAIM CAM — OBS Source</div>
    <div class="subtitle">Waiting for stream… (Milestone 1 placeholder)</div>
  </div>
  <script>
    // TODO(milestone-3): Connect to WebRTC peer via signaling and display
    // the remote video track in #remote-video.
    console.log('[OBS Source] Loaded — no WebRTC in Milestone 1');
  </script>
</body>
</html>`;

/**
 * Local HTTP server that serves the OBS Browser Source page.
 *
 * OBS Studio adds this as a Browser Source at http://localhost:8766
 *
 * Milestone 1: serves a static placeholder page.
 * TODO(milestone-3): Serve a page that connects WebRTC and renders the video.
 */
export class ObsSourceServer {
    private _server: http.Server | null = null;
    private readonly _port: number;

    constructor(port: number) {
        this._port = port;
    }

    start(): Promise<void> {
        return new Promise((resolve, reject) => {
            this._server = http.createServer((_req, res) => {
                res.writeHead(200, {
                    'Content-Type': 'text/html; charset=utf-8',
                    'Cache-Control': 'no-cache',
                    'Access-Control-Allow-Origin': '*',
                });
                res.end(OBS_SOURCE_HTML);
            });

            this._server.on('error', (err: Error) => {
                console.error('[ObsSourceServer] Error:', err.message);
                reject(err);
            });

            this._server.listen(this._port, '127.0.0.1', () => {
                console.log(`[ObsSourceServer] Serving OBS source at http://localhost:${this._port}`);
                resolve();
            });
        });
    }

    stop(): Promise<void> {
        return new Promise(resolve => {
            if (!this._server) {
                resolve();
                return;
            }
            this._server.close(() => {
                console.log('[ObsSourceServer] Stopped');
                this._server = null;
                resolve();
            });
        });
    }
}
