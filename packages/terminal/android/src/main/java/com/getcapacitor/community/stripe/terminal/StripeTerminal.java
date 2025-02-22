package com.getcapacitor.community.stripe.terminal;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.util.Supplier;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.getcapacitor.community.stripe.terminal.models.Executor;
import com.google.android.gms.common.util.BiConsumer;
import com.stripe.stripeterminal.Terminal;
import com.stripe.stripeterminal.TerminalApplicationDelegate;
import com.stripe.stripeterminal.external.callable.Callback;
import com.stripe.stripeterminal.external.callable.Cancelable;
import com.stripe.stripeterminal.external.callable.DiscoveryListener;
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback;
import com.stripe.stripeterminal.external.callable.ReaderCallback;
import com.stripe.stripeterminal.external.callable.TerminalListener;
import com.stripe.stripeterminal.external.models.ConnectionConfiguration;
import com.stripe.stripeterminal.external.models.ConnectionStatus;
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration;
import com.stripe.stripeterminal.external.models.DiscoveryMethod;
import com.stripe.stripeterminal.external.models.PaymentIntent;
import com.stripe.stripeterminal.external.models.PaymentStatus;
import com.stripe.stripeterminal.external.models.Reader;
import com.stripe.stripeterminal.external.models.TerminalException;
import com.stripe.stripeterminal.log.LogLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.json.JSONException;

public class StripeTerminal extends Executor {

    private Cancelable discoveryCancelable;
    private Cancelable collectCancelable;
    private List<Reader> readers;
    private String locationId;
    private PluginCall collectCall;
    private final JSObject emptyObject = new JSObject();
    private Boolean isTest;
    private DiscoveryMethod type;

    public StripeTerminal(
        Supplier<Context> contextSupplier,
        Supplier<Activity> activitySupplier,
        BiConsumer<String, JSObject> notifyListenersFunction,
        String pluginLogTag
    ) {
        super(contextSupplier, activitySupplier, notifyListenersFunction, pluginLogTag, "StripeTerminalExecutor");
        this.contextSupplier = contextSupplier;
        this.readers = new ArrayList<Reader>();
    }

    public void initialize(final PluginCall call) throws TerminalException {
        this.isTest = call.getBoolean("isTest", true);

        BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
        if (!bluetooth.isEnabled()) {
            if (
                ActivityCompat.checkSelfPermission(this.contextSupplier.get(), Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                bluetooth.enable();
            }
        }

        this.activitySupplier.get()
            .runOnUiThread(
                () -> {
                    TerminalApplicationDelegate.onCreate((Application) this.contextSupplier.get().getApplicationContext());
                    notifyListeners(TerminalEnumEvent.Loaded.getWebEventName(), emptyObject);
                    call.resolve();
                }
            );
        TerminalListener listener = new TerminalListener() {
            @Override
            public void onUnexpectedReaderDisconnect(@NonNull Reader reader) {
                // TODO: Listenerを追加
            }
            //
            //            @Override
            //            public void onConnectionStatusChange(@NonNull ConnectionStatus status) {
            //                // TODO: Listenerを追加
            //            }
            //
            //            @Override
            //            public void onPaymentStatusChange(@NonNull PaymentStatus status) {
            //                // TODO: Listenerを追加
            //            }
        };
        LogLevel logLevel = LogLevel.VERBOSE;
        TokenProvider tokenProvider = new TokenProvider(this.contextSupplier, call.getString("tokenProviderEndpoint"));
        if (!Terminal.isInitialized()) {
            Terminal.initTerminal(this.contextSupplier.get().getApplicationContext(), logLevel, tokenProvider, listener);
        }
        Terminal.getInstance();
    }

    public void onDiscoverReaders(final PluginCall call) {
        this.locationId = call.getString("locationId");
        if (Objects.equals(call.getString("type"), TerminalConnectTypes.TapToPay.getWebEventName())) {
            this.type = DiscoveryMethod.LOCAL_MOBILE;
        } else if (Objects.equals(call.getString("type"), TerminalConnectTypes.Internet.getWebEventName())) {
            this.type = DiscoveryMethod.INTERNET;
        } else {
            call.unimplemented(call.getString("type") + " is not support now");
            return;
        }

        final DiscoveryConfiguration config = new DiscoveryConfiguration(0, this.type, this.isTest, call.getString("locationId"));
        final DiscoveryListener discoveryListener = readers -> {
            // 検索したReaderの一覧をListenerで渡す
            Log.d(logTag, String.valueOf(readers.get(0).getSerialNumber()));
            this.readers = readers;
            JSArray readersJSObject = new JSArray();

            int i = 0;
            for (Reader reader : this.readers) {
                readersJSObject.put(new JSObject().put("index", String.valueOf(i)).put("serialNumber", reader.getSerialNumber()));
            }
            this.notifyListeners(TerminalEnumEvent.DiscoveredReaders.getWebEventName(), new JSObject().put("readers", readersJSObject));
            call.resolve(new JSObject().put("readers", readersJSObject));
        };
        discoveryCancelable =
            Terminal
                .getInstance()
                .discoverReaders(
                    config,
                    discoveryListener,
                    // Callback run after connectReader
                    new Callback() {
                        @Override
                        public void onSuccess() {
                            Log.d(logTag, "Finished discovering readers");
                        }

                        @Override
                        public void onFailure(@NonNull TerminalException ex) {
                            Log.d(logTag, ex.getLocalizedMessage());
                        }
                    }
                );
    }

    public void connectReader(final PluginCall call) {
        if (this.type == DiscoveryMethod.LOCAL_MOBILE) {
            this.connectLocalMobileReader(call);
        } else if (this.type == DiscoveryMethod.INTERNET) {
            this.connectInternetReader(call);
        }
    }

    public void getConnectedReader(final PluginCall call) {
        Reader reader = Terminal.getInstance().getConnectedReader();

        if (reader == null) {
            call.resolve(new JSObject().put("reader", JSObject.NULL));
        } else {
            call.resolve(new JSObject().put("reader", new JSObject().put("serialNumber", reader.getSerialNumber())));
        }
    }

    public void disconnectReader(final PluginCall call) {
        if (Terminal.getInstance().getConnectedReader() == null) {
            call.resolve();
            return;
        }

        Terminal
            .getInstance()
            .disconnectReader(
                new Callback() {
                    @Override
                    public void onSuccess() {
                        notifyListeners(TerminalEnumEvent.DisconnectedReader.getWebEventName(), emptyObject);
                        call.resolve();
                    }

                    @Override
                    public void onFailure(@NonNull TerminalException ex) {
                        call.reject(ex.getLocalizedMessage(), ex);
                    }
                }
            );
    }

    private void connectLocalMobileReader(final PluginCall call) {
        JSObject reader = call.getObject("reader");
        ConnectionConfiguration.LocalMobileConnectionConfiguration config = new ConnectionConfiguration.LocalMobileConnectionConfiguration(
            this.locationId
        );
        Terminal
            .getInstance()
            .connectLocalMobileReader(
                this.readers.get(reader.getInteger("index")),
                config,
                new ReaderCallback() {
                    @Override
                    public void onSuccess(Reader r) {
                        notifyListeners(TerminalEnumEvent.ConnectedReader.getWebEventName(), emptyObject);
                        call.resolve();
                    }

                    @Override
                    public void onFailure(@NonNull TerminalException ex) {
                        ex.printStackTrace();
                        call.reject(ex.getLocalizedMessage(), ex);
                    }
                }
            );
    }

    private void connectInternetReader(final PluginCall call) {
        JSObject reader = call.getObject("reader");
        ConnectionConfiguration.InternetConnectionConfiguration config = new ConnectionConfiguration.InternetConnectionConfiguration();
        Terminal
            .getInstance()
            .connectInternetReader(
                this.readers.get(reader.getInteger("index")),
                config,
                new ReaderCallback() {
                    @Override
                    public void onSuccess(@NonNull Reader r) {
                        notifyListeners(TerminalEnumEvent.ConnectedReader.getWebEventName(), emptyObject);
                        call.resolve();
                    }

                    @Override
                    public void onFailure(@NonNull TerminalException ex) {
                        ex.printStackTrace();
                        call.reject(ex.getLocalizedMessage(), ex);
                    }
                }
            );
    }

    public void cancelDiscoverReaders(final PluginCall call) {
        if (discoveryCancelable != null) {
            discoveryCancelable.cancel(
                new Callback() {
                    @Override
                    public void onSuccess() {
                        notifyListeners(TerminalEnumEvent.CancelDiscoveredReaders.getWebEventName(), emptyObject);
                        call.resolve();
                    }

                    @Override
                    public void onFailure(TerminalException ex) {
                        call.reject(ex.getLocalizedMessage(), ex);
                    }
                }
            );
        } else {
            call.resolve();
        }
    }

    public void collect(final PluginCall call) {
        // メソッドを分割するためcallを永続化
        this.collectCall = call;

        Terminal.getInstance().retrievePaymentIntent(call.getString("paymentIntent"), createPaymentIntentCallback);
    }

    public void cancelCollect(final PluginCall call) {
        if (this.collectCancelable == null || this.collectCancelable.isCompleted()) {
            call.resolve();
            return;
        }

        this.collectCancelable.cancel(
                new Callback() {
                    @Override
                    public void onSuccess() {
                        collectCancelable = null;
                        notifyListeners(TerminalEnumEvent.Canceled.getWebEventName(), emptyObject);
                        call.resolve();
                    }

                    @Override
                    public void onFailure(@NonNull TerminalException e) {
                        call.reject(e.getErrorMessage());
                    }
                }
            );
    }

    private final PaymentIntentCallback createPaymentIntentCallback = new PaymentIntentCallback() {
        @Override
        public void onSuccess(@NonNull PaymentIntent paymentIntent) {
            collectCancelable = Terminal.getInstance().collectPaymentMethod(paymentIntent, collectPaymentMethodCallback);
        }

        @Override
        public void onFailure(@NonNull TerminalException ex) {
            notifyListeners(TerminalEnumEvent.Failed.getWebEventName(), emptyObject);
            collectCall.reject(ex.getLocalizedMessage(), ex);
        }
    };

    // Step 3 - we've collected the payment method, so it's time to process the payment
    private final PaymentIntentCallback collectPaymentMethodCallback = new PaymentIntentCallback() {
        @Override
        public void onSuccess(@NonNull PaymentIntent paymentIntent) {
            collectCancelable = null;
            Terminal.getInstance().processPayment(paymentIntent, processPaymentCallback);
        }

        @Override
        public void onFailure(@NonNull TerminalException ex) {
            collectCancelable = null;
            notifyListeners(TerminalEnumEvent.Failed.getWebEventName(), emptyObject);
            collectCall.reject(ex.getLocalizedMessage(), ex);
        }
    };

    // Step 4 - we've processed the payment! Show a success screen
    private final PaymentIntentCallback processPaymentCallback = new PaymentIntentCallback() {
        @Override
        public void onSuccess(@NonNull PaymentIntent paymentIntent) {
            notifyListeners(TerminalEnumEvent.Completed.getWebEventName(), emptyObject);
            collectCall.resolve();
        }

        @Override
        public void onFailure(@NonNull TerminalException ex) {
            notifyListeners(TerminalEnumEvent.Failed.getWebEventName(), emptyObject);
            collectCall.reject(ex.getLocalizedMessage(), ex);
        }
    };
}
