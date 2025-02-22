package io.ionic.starter;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerPlugin(com.getcapacitor.community.stripe.StripePlugin.class);
        registerPlugin(com.getcapacitor.community.stripe.identity.StripeIdentityPlugin.class);
        registerPlugin(com.getcapacitor.community.stripe.terminal.StripeTerminalPlugin.class);
    }
}
