package com.waze.wazeology;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/**
 * UI chrome copy for the Wazeology screen, resolved once from the current device locale. Brazilian
 * Portuguese (pt-BR) when the device is set to Português (Brasil); English everywhere else. Mirrors
 * {@link Palette}: no new resources (the APK's resources stay pristine), so every string is a literal
 * parsed here rather than an {@code R.string} lookup. Diagnostic Log-tab lines are intentionally not
 * translated. Name-bearing labels are methods because word order differs between the two languages.
 */
final class Strings {

    /** True when the device is set to Português (Brasil). */
    final boolean pt;

    // Tabs
    final String tabMotorcycle;
    final String tabLog;

    // Card titles
    final String connection;
    final String devices;

    // Connection-card buttons
    final String scanForMotorcycle;
    final String forget;

    // Log-tab buttons
    final String share;
    final String copy;
    final String clear;

    // Passkey banner
    final String passkeyTitle;
    final String passkeyBody;

    // Unsupported-cluster banner
    final String unsupportedTitle;
    final String unsupportedBody;

    // State labels
    final String connected;
    final String pairing;
    final String subscribing;
    final String initializing;
    final String bluetoothOff;
    final String notConnected;
    final String permissionNeeded;

    // Contextual primary-button labels
    final String turnOnBluetooth;
    final String openAppSettings;
    final String scanning;
    final String disconnect;
    final String connectNow;
    final String connect;

    // Devices-list placeholders
    final String searchingDevices;
    final String noMotorcycleFound;
    final String noDevicesYet;

    // Fragments
    final String unnamed;             // device with no advertised name
    final String motorcycleFallback;  // targetName() noun when no label is known
    final String cuePrefix;           // "Cue: " before the live Waze cue
    final String selectedSuffix;      // ", selected" appended to a selected tab's description
    final String connectToPrefix;     // "Connect to " before a device name (row description)

    // Share-sheet chrome
    final String shareSubject;
    final String shareChooser;

    Strings(Context context) {
        Locale loc = localeOf(context);
        this.pt = "pt".equals(loc.getLanguage()) && "BR".equalsIgnoreCase(loc.getCountry());
        if (pt) {
            tabMotorcycle      = "Moto";
            tabLog             = "Registro";
            connection         = "Conexão";
            devices            = "Dispositivos";
            scanForMotorcycle  = "Buscar motocicleta";
            forget             = "Esquecer";
            share              = "Compartilhar";
            copy               = "Copiar";
            clear              = "Limpar";
            passkeyTitle       = "Confirmar pareamento";
            passkeyBody        = "Confira se o código do painel é igual ao do telefone e confirme nos dois aparelhos.";
            unsupportedTitle   = "Este painel não mostra navegação";
            unsupportedBody    = "É um modelo ou firmware diferente do que o Wazeology suporta.";
            connected          = "Conectado";
            pairing            = "Pareando: confirme o código";
            subscribing        = "Preparando…";
            initializing       = "Inicializando…";
            bluetoothOff       = "Bluetooth desligado";
            notConnected       = "Não conectado";
            permissionNeeded   = "Permissão de Bluetooth necessária";
            turnOnBluetooth    = "Ativar Bluetooth";
            openAppSettings    = "Abrir configurações do app";
            scanning           = "Buscando…";
            disconnect         = "Desconectar";
            connectNow         = "Conectar agora";
            connect            = "Conectar";
            searchingDevices   = "Buscando sua motocicleta…";
            noMotorcycleFound  = "Nenhuma motocicleta encontrada. Confira se o painel está ligado e busque de novo.";
            noDevicesYet       = "(nenhum dispositivo ainda: toque em Buscar)";
            unnamed            = "(sem nome)";
            motorcycleFallback = "motocicleta";
            cuePrefix          = "Instrução: ";
            selectedSuffix     = ", aba selecionada";
            connectToPrefix    = "Conectar a ";
            shareSubject       = "Registro da motocicleta Kawasaki";
            shareChooser       = "Compartilhar registro da motocicleta";
        } else {
            tabMotorcycle      = "Motorcycle";
            tabLog             = "Log";
            connection         = "Connection";
            devices            = "Devices";
            scanForMotorcycle  = "Scan for motorcycle";
            forget             = "Forget";
            share              = "Share";
            copy               = "Copy";
            clear              = "Clear";
            passkeyTitle       = "Confirm pairing";
            passkeyBody        = "Check the passkey on your cluster matches the phone's, then confirm on both.";
            unsupportedTitle   = "This cluster can't show navigation";
            unsupportedBody    = "It's a different model or firmware than Wazeology supports.";
            connected          = "Connected";
            pairing            = "Pairing — enter passkey";
            subscribing        = "Subscribing…";
            initializing       = "Initializing…";
            bluetoothOff       = "Bluetooth is off";
            notConnected       = "Not connected";
            permissionNeeded   = "Bluetooth permission needed";
            turnOnBluetooth    = "Turn on Bluetooth";
            openAppSettings    = "Open app settings";
            scanning           = "Scanning…";
            disconnect         = "Disconnect";
            connectNow         = "Connect now";
            connect            = "Connect";
            searchingDevices   = "Searching for your motorcycle…";
            noMotorcycleFound  = "No motorcycle found. Make sure the cluster is powered on, then scan again.";
            noDevicesYet       = "(no devices yet — tap Scan)";
            unnamed            = "(unnamed)";
            motorcycleFallback = "motorcycle";
            cuePrefix          = "Cue: ";
            selectedSuffix     = ", selected";
            connectToPrefix    = "Connect to ";
            shareSubject       = "Kawasaki motorcycle log";
            shareChooser       = "Share motorcycle log";
        }
    }

    String waitingFor(String name) {
        return pt ? "Aguardando " + name + "…" : "Waiting for " + name + "…";
    }

    String connectingTo(String name) {
        return pt ? "Conectando a " + name + "…" : "Connecting to " + name + "…";
    }

    String savedDisconnected(String name) {
        return pt ? "Salvo: " + name + ", desconectado" : "Saved: " + name + " — disconnected";
    }

    String savedBluetoothOff(String name) {
        return pt ? "Salvo: " + name + ", Bluetooth desligado" : "Saved: " + name + " — Bluetooth off";
    }

    String savedNeedsPairing(String name) {
        return pt ? "Salvo: " + name + ", precisa parear" : "Saved: " + name + " — needs pairing";
    }

    @SuppressWarnings("deprecation")
    private static Locale localeOf(Context context) {
        Configuration config = context.getResources().getConfiguration();
        Locale loc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            ? config.getLocales().get(0) // may be null if the LocaleList is empty
            : config.locale;
        return loc != null ? loc : Locale.getDefault();
    }
}
