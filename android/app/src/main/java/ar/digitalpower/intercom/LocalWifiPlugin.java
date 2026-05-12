package ar.digitalpower.intercom;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;
import android.Manifest;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.List;

@CapacitorPlugin(
    name = "LocalWifi",
    permissions = {
        @Permission(
            alias = "location",
            strings = { Manifest.permission.ACCESS_FINE_LOCATION }
        )
    }
)
public class LocalWifiPlugin extends Plugin {

    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager connectivityManager;

    @PluginMethod
    public void connect(PluginCall call) {
        String ssid = call.getString("ssid");
        String password = call.getString("password");

        if (ssid == null || ssid.isEmpty()) {
            call.reject("Debes proveer un SSID");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWifiQ(call, ssid, password);
        } else {
            call.reject("Esta función requiere Android 10 (API 29) o superior.");
        }
    }

    @PluginMethod
    public void scanNetworks(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "locationCallback");
        } else {
            doScan(call);
        }
    }

    @PermissionCallback
    private void locationCallback(PluginCall call) {
        if (getPermissionState("location") == PermissionState.GRANTED) {
            doScan(call);
        } else {
            call.reject("Permiso de ubicación denegado. Es necesario para escanear redes WiFi.");
        }
    }

    private void doScan(PluginCall call) {
        try {
            WifiManager wifiManager = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            List<ScanResult> scanResults = wifiManager.getScanResults();
            JSArray networks = new JSArray();
            
            for (ScanResult result : scanResults) {
                if (result.SSID != null && !result.SSID.isEmpty()) {
                    JSObject network = new JSObject();
                    network.put("ssid", result.SSID);
                    network.put("level", result.level);
                    network.put("bssid", result.BSSID);
                    networks.put(network);
                }
            }
            
            JSObject ret = new JSObject();
            ret.put("networks", networks);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Error al escanear redes: " + e.getMessage());
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    connectivityManager.bindProcessToNetwork(null); // Desvincula la red, vuelve a usar Datos Móviles
                } else {
                    ConnectivityManager.setProcessDefaultNetwork(null);
                }
            } catch (Exception e) {
                Log.e("LocalWifi", "Error desconectando", e);
            }
            networkCallback = null;
        }
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void connectWifiQ(final PluginCall call, String ssid, String password) {
        WifiNetworkSpecifier.Builder specifierBuilder = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid);

        if (password != null && !password.isEmpty()) {
            specifierBuilder.setWpa2Passphrase(password);
        }

        WifiNetworkSpecifier specifier = specifierBuilder.build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Esto evita que Android intente rutear el internet por aquí
                .setNetworkSpecifier(specifier)
                .build();

        connectivityManager = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        // Si ya había una conexión activa con el plugin, la limpiamos
        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                Log.i("LocalWifiPlugin", "Red conectada: " + network.toString());
                
                // Forzamos a que todo el WebView y la App de Capacitor use ESTA red WiFi,
                // pero el resto de las apps del teléfono siguen usando la red principal (Datos Móviles)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    connectivityManager.bindProcessToNetwork(network);
                } else {
                    ConnectivityManager.setProcessDefaultNetwork(network);
                }

                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("network", network.toString());
                call.resolve(ret);
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                Log.e("LocalWifiPlugin", "Red no disponible o el usuario canceló.");
                call.reject("No se pudo conectar a la red WiFi del intercomunicador.");
                networkCallback = null;
            }
        };

        // Solicitar la conexión
        connectivityManager.requestNetwork(request, networkCallback);
    }
}
