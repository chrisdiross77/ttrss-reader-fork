/*
 * ttrss-reader-fork for Android
 * 
 * Copyright (C) 2010 N. Braden.
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 3 as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package org.ttrssreader.net;

import java.io.File;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.SocketException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.json.JSONObject;
import org.ttrssreader.controllers.Controller;
import org.ttrssreader.utils.Utils;
import android.content.Context;
import android.os.Build;
import android.util.Log;

public class JavaJSONConnector extends JSONConnector {
    
    SSLSocketFactory sslSocketFactory = null;
    
    public JavaJSONConnector(Context context) {
        super(context);
        disableConnectionReuseIfNecessary();
        enableHttpResponseCache(context);
        try {
            setupKeystore();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    protected InputStream doRequest(Map<String, String> params) {
        try {
            if (sessionId != null)
                params.put(SID, sessionId);
            
            JSONObject json = new JSONObject(params);
            byte[] outputBytes = json.toString().getBytes("UTF-8");
            
            logRequest(json);
            refreshHTTPAuth();
            
            // Create Connection
            HttpURLConnection con = (HttpURLConnection) Controller.getInstance().url().openConnection();
            con.setDoInput(true);
            con.setDoOutput(true);
            con.setUseCaches(false);
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Content-Length", Integer.toString(outputBytes.length));
            
            int timeoutSocket = (int) ((Controller.getInstance().lazyServer()) ? 15 * Utils.MINUTE : 10 * Utils.SECOND);
            con.setReadTimeout(timeoutSocket);
            con.setConnectTimeout((int) (8 * Utils.SECOND));
            
            // Add POST data
            con.getOutputStream().write(outputBytes);
            
            // Try to check for HTTP Status codes
            int code = con.getResponseCode();
            if (code >= 400 && code < 600) {
                hasLastError = true;
                lastError = "Server returned status: " + code + " (Message: " + con.getResponseMessage() + ")";
                return null;
            }
            return con.getInputStream();
            
        } catch (SSLPeerUnverifiedException e) {
            // Probably related: http://stackoverflow.com/questions/6035171/no-peer-cert-not-sure-which-route-to-take
            // Not doing anything here since this error should happen only when no certificate is received from the
            // server.
            Log.w(Utils.TAG, "SSLPeerUnverifiedException in doRequest(): " + formatException(e));
        } catch (SSLException e) {
            if ("No peer certificate".equals(e.getMessage())) {
                // Handle this by ignoring it, this occurrs very often when the connection is instable.
                Log.w(Utils.TAG, "SSLException in doRequest(): " + formatException(e));
            } else {
                hasLastError = true;
                lastError = "SSLException in doRequest(): " + formatException(e);
            }
        } catch (InterruptedIOException e) {
            // http://stackoverflow.com/questions/693997/how-to-set-httpresponse-timeout-for-android-in-java/1565243#1565243
            Log.w(Utils.TAG, "InterruptedIOException in doRequest(): " + formatException(e));
        } catch (SocketException e) {
            // http://stackoverflow.com/questions/693997/how-to-set-httpresponse-timeout-for-android-in-java/1565243#1565243
            Log.w(Utils.TAG, "SocketException in doRequest(): " + formatException(e));
        } catch (Exception e) {
            hasLastError = true;
            lastError = "Exception in doRequest(): " + formatException(e);
            e.printStackTrace();
        }
        
        return null;
    }
    
    protected void setupKeystore() throws Exception {
        // Initialize the unsecure SSL stuff
        trustAll(Controller.getInstance().trustAllSsl(), Controller.getInstance().trustAllHosts());
        
        if (sslSocketFactory == null && Controller.getInstance().useKeystore()) {
            KeyStore keystore = Utils.loadKeystore(Controller.getInstance().getKeystorePassword());
            if (keystore == null)
                return;
            
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keystore);
            
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keystore, Controller.getInstance().getKeystorePassword().toCharArray());
            
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new java.security.SecureRandom());
            
            sslSocketFactory = sc.getSocketFactory();
            HttpsURLConnection.setDefaultSSLSocketFactory(sslSocketFactory);
        }
    }
    
    protected static void trustAll(boolean trustAnyCert, boolean trustAnyHost) {
        try {
            if (trustAnyCert) {
                X509TrustManager easyTrustManager = new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }
                    
                    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }
                    
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                    
                };
                
                // Create a trust manager that does not validate certificate chains
                TrustManager[] trustAllCerts = new TrustManager[] { easyTrustManager };
                
                // Install the all-trusting trust manager
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAllCerts, new java.security.SecureRandom());
                HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            }
            if (trustAnyHost) {
                HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    @Override
    protected void refreshHTTPAuth() {
        if (!httpAuth)
            return;
        
        Authenticator.setDefault(new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(httpUsername, httpPassword.toCharArray());
            }
        });
    }
    
    @SuppressWarnings("deprecation")
    private void disableConnectionReuseIfNecessary() {
        // Work around pre-Froyo bugs in HTTP connection reuse.
        if (Integer.parseInt(Build.VERSION.SDK) < Build.VERSION_CODES.FROYO) {
            System.setProperty("http.keepAlive", "false");
        }
    }
    
    /**
     * Enables HTTP response caching on devices that support it, see
     * http://android-developers.blogspot.de/2011/09/androids-http-clients.html
     * 
     * @param context
     */
    private void enableHttpResponseCache(Context context) {
        try {
            long httpCacheSize = 10 * 1024 * 1024; // 10 MiB
            File httpCacheDir = new File(context.getCacheDir(), "http");
            Class.forName("android.net.http.HttpResponseCache").getMethod("install", File.class, long.class)
                    .invoke(null, httpCacheDir, httpCacheSize);
        } catch (Exception httpResponseCacheNotAvailable) {
        }
    }
    
}
