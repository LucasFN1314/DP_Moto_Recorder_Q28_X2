package ar.digitalpower.intercom;

import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.graphics.Color;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;

@CapacitorPlugin(name = "VideoPreview")
public class VideoPreviewPlugin extends Plugin {

    private SurfaceView surfaceView;
    private LibVLC libvlc;
    private MediaPlayer mediaPlayer;

    @PluginMethod
    public void start(PluginCall call) {
        String url = call.getString("url");
        Double x = call.getDouble("x", 0.0);
        Double y = call.getDouble("y", 0.0);
        Double width = call.getDouble("width", 0.0);
        Double height = call.getDouble("height", 0.0);

        if (url == null) {
            call.reject("URL is required");
            return;
        }

        getActivity().runOnUiThread(() -> {
            try {
                // Make WebView transparent
                bridge.getWebView().setBackgroundColor(Color.TRANSPARENT);

                if (surfaceView != null) {
                    ((ViewGroup) surfaceView.getParent()).removeView(surfaceView);
                }
                if (mediaPlayer != null) {
                    mediaPlayer.stop();
                    mediaPlayer.getVLCVout().detachViews();
                    mediaPlayer.release();
                    mediaPlayer = null;
                }
                if (libvlc != null) {
                    libvlc.release();
                    libvlc = null;
                }

                surfaceView = new SurfaceView(getContext());
                
                int pxX = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.floatValue(), getContext().getResources().getDisplayMetrics());
                int pxY = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, y.floatValue(), getContext().getResources().getDisplayMetrics());
                int pxWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, width.floatValue(), getContext().getResources().getDisplayMetrics());
                int pxHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, height.floatValue(), getContext().getResources().getDisplayMetrics());

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(pxWidth, pxHeight);
                params.leftMargin = pxX;
                params.topMargin = pxY;

                surfaceView.setLayoutParams(params);

                ArrayList<String> options = new ArrayList<>();
                options.add("--rtsp-tcp"); // Prefer TCP for stability
                options.add("--drop-late-frames");
                options.add("--skip-frames");
                options.add("--no-audio"); // Mute audio
                options.add("-vvv");
                
                libvlc = new LibVLC(getContext(), options);
                mediaPlayer = new MediaPlayer(libvlc);

                mediaPlayer.getVLCVout().setVideoView(surfaceView);
                mediaPlayer.getVLCVout().setWindowSize(pxWidth, pxHeight);
                mediaPlayer.getVLCVout().attachViews();

                Media media = new Media(libvlc, Uri.parse(url));
                media.setHWDecoderEnabled(true, false);
                media.addOption(":network-caching=300"); // 300ms latency buffer
                mediaPlayer.setMedia(media);
                media.release();

                mediaPlayer.play();

                // Add behind webview
                ((ViewGroup) bridge.getWebView().getParent()).addView(surfaceView, 0);

                call.resolve(new JSObject().put("success", true));
            } catch (Exception e) {
                call.reject("Error starting preview", e);
            }
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (surfaceView != null && surfaceView.getParent() != null) {
                ((ViewGroup) surfaceView.getParent()).removeView(surfaceView);
            }
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.getVLCVout().detachViews();
                mediaPlayer.release();
                mediaPlayer = null;
            }
            if (libvlc != null) {
                libvlc.release();
                libvlc = null;
            }
            surfaceView = null;
            call.resolve(new JSObject().put("success", true));
        });
    }
}
