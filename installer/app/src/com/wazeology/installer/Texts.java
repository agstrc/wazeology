package com.wazeology.installer;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import com.wazeology.core.Outcome;
import com.wazeology.core.Pins;
import com.wazeology.installer.core.JobState;

import java.util.Locale;

/**
 * Every user-facing string of the installer, in Portuguese (pt-BR) when the device is set to
 * Português (Brasil) and English everywhere else, mirroring the Wazeology screen's Strings. Words
 * like patch, graft, sign, split or hash never reach the rider; the technical detail stays in the
 * details log.
 */
final class Texts {

    final boolean pt;

    Texts(Context context) {
        Locale loc = localeOf(context);
        pt = "pt".equals(loc.getLanguage()) && "BR".equalsIgnoreCase(loc.getCountry());
    }

    private String t(String en, String ptBr) {
        return pt ? ptBr : en;
    }

    static final String V = Pins.WAZE_VERSION_NAME;

    // --- header ---

    String appName() {
        return "Wazeology Installer";
    }

    String unofficial() {
        return t("Unofficial. Not made by or affiliated with Waze, Google or Kawasaki.",
                "Não oficial. Não é feito pela Waze, Google ou Kawasaki, nem tem vínculo com elas.");
    }

    String unsupportedTitle() {
        return t("This phone can't run this Waze", "Este celular não roda este Waze");
    }

    String unsupportedAndroid(int sdk) {
        return t("Waze " + V + " needs Android 12L or newer. This phone runs an older Android (API "
                        + sdk + ").",
                "O Waze " + V + " precisa do Android 12L ou mais novo. Este celular tem um Android mais "
                        + "antigo (API " + sdk + ").");
    }

    String unsupportedAbi() {
        return t("Waze " + V + " only exists for ARM processors, and this phone uses a different one.",
                "O Waze " + V + " só existe para processadores ARM, e este celular usa outro tipo.");
    }

    // --- current Waze card ---

    String wazeCardTitle() {
        return t("Waze on this phone", "Waze neste celular");
    }

    String wazeOther(String version) {
        return t("Another Waze (" + version + ") is installed, probably from the Play Store. Android "
                        + "allows only one Waze, so remove it before installing Waze with Wazeology. "
                        + "Anything saved to your Waze account comes back when you sign in again.",
                "Outro Waze (" + version + ") está instalado, provavelmente da Play Store. O Android só "
                        + "permite um Waze, então remova esse antes de instalar o Waze com Wazeology. "
                        + "O que estiver salvo na sua conta do Waze volta quando você entrar de novo.");
    }

    String wazeOursNewer(String version) {
        return t("A newer Waze with Wazeology (" + version + ") is installed. Android can't go back to "
                        + V + " over it, so remove it first.",
                "Um Waze com Wazeology mais novo (" + version + ") está instalado. O Android não volta "
                        + "para a " + V + " por cima dele, então remova-o antes.");
    }

    String wazeOurs(String version) {
        return t("Waze with Wazeology " + version + " is installed.",
                "Waze com Wazeology " + version + " está instalado.");
    }

    String wazeSystem() {
        return t("Waze came preinstalled on this phone and can't be removed, so Waze with Wazeology "
                        + "can't be installed here.",
                "O Waze veio de fábrica neste celular e não pode ser removido, então o Waze com "
                        + "Wazeology não pode ser instalado aqui.");
    }

    String wazeOtherProfile() {
        return t("Waze is still installed in another profile on this phone (for example a work profile "
                        + "or Secure Folder). Remove it there, then come back.",
                "O Waze ainda está instalado em outro perfil deste celular (por exemplo, um perfil de "
                        + "trabalho ou a Pasta Segura). Remova-o lá e depois volte aqui.");
    }

    String removeWaze() {
        return t("Remove current Waze", "Remover o Waze atual");
    }

    String removeConfirmTitle() {
        return t("Remove Waze?", "Remover o Waze?");
    }

    String removeConfirmBody() {
        return t("Android will ask you to confirm. Waze data kept only on this phone is deleted; "
                        + "anything saved to your Waze account comes back when you sign in again.",
                "O Android vai pedir confirmação. Os dados do Waze guardados só neste celular são "
                        + "apagados; o que estiver salvo na sua conta do Waze volta quando você entrar "
                        + "de novo.");
    }

    String remove() {
        return t("Remove", "Remover");
    }

    String cancel() {
        return t("Cancel", "Cancelar");
    }

    // --- step 1: prepare ---

    String prepareTitle() {
        return t("1. Prepare Waze with Wazeology", "1. Preparar o Waze com Wazeology");
    }

    String prepareIntro() {
        return t("Downloads Waze " + V + " from APKPure (about 181 MB, Wi-Fi recommended) and adds "
                        + "Wazeology to it. Everything happens on this phone and nothing is uploaded.",
                "Baixa o Waze " + V + " do APKPure (cerca de 181 MB, de preferência no Wi-Fi) e "
                        + "adiciona o Wazeology. Tudo acontece neste celular e nada é enviado.");
    }

    String preparePartial(long have, long total) {
        String of = total > 0 ? mb(have) + " / " + mb(total) : mb(have);
        return t("The download stopped at " + of + ". Continue to pick up where it left off.",
                "O download parou em " + of + ". Continue para retomar de onde parou.");
    }

    String prepareSourceReady() {
        return t("Waze is downloaded. Continue to add Wazeology.",
                "O Waze já foi baixado. Continue para adicionar o Wazeology.");
    }

    String prepareReady() {
        return t("✓ Ready. Waze with Wazeology is prepared.",
                "✓ Pronto. O Waze com Wazeology está preparado.");
    }

    String prepareButton() {
        return t("Prepare Waze with Wazeology", "Preparar o Waze com Wazeology");
    }

    String continueButton() {
        return t("Continue", "Continuar");
    }

    String stopping() {
        return t("Stopping…", "Parando…");
    }

    String pickFiles() {
        return t("Use Waze files I already have", "Usar arquivos do Waze que já tenho");
    }

    // --- step 2: install ---

    String installTitle() {
        return t("2. Install", "2. Instalar");
    }

    String installNotReady() {
        return t("Prepare Waze with Wazeology first.", "Prepare o Waze com Wazeology primeiro.");
    }

    String installRemoveFirst() {
        return t("Remove the current Waze first (see above).", "Remova o Waze atual primeiro (veja acima).");
    }

    String installBlockedSystem() {
        return t("Not possible on this phone (see above).", "Não é possível neste celular (veja acima).");
    }

    String installHint() {
        return t("Android asks you to confirm. If it warns about an unrecognized app, tap More details, "
                        + "then Install anyway.",
                "O Android pede confirmação. Se ele avisar sobre um app não reconhecido, toque em Mais "
                        + "detalhes e depois em Instalar mesmo assim.");
    }

    String installUpdateHint() {
        return t("Updates your Waze with Wazeology. Your Waze data stays.",
                "Atualiza o seu Waze com Wazeology. Os dados do Waze continuam.");
    }

    String installedBody() {
        return t("You now have two icons: Waze and Wazeology. Open Wazeology, tap Scan for "
                        + "motorcycle, pick yours and confirm the pairing on the dashboard. Then start a route in Waze.\n\n"
                        + "Tip: in the Play Store, open Waze, tap ⋮ and turn off \"Enable auto update\" "
                        + "so the Play Store doesn't replace it.",
                "Agora você tem dois ícones: Waze e Wazeology. Abra o Wazeology, toque em Buscar moto, "
                        + "escolha a sua e confirme o pareamento no painel. Depois inicie uma rota no Waze.\n\n"
                        + "Dica: na Play Store, abra o Waze, toque em ⋮ e desligue \"Ativar atualização "
                        + "automática\" para a Play Store não substituí-lo.");
    }

    String awaitingConfirm() {
        return t("Waiting for you to confirm the install in Android's window.",
                "Aguardando você confirmar a instalação na janela do Android.");
    }

    String androidInstalling() {
        return t("Android is installing Waze with Wazeology", "O Android está instalando o Waze com Wazeology");
    }

    String androidInstallingDetail() {
        return t("This takes a moment. You can leave this screen.",
                "Isso leva um instante. Você pode sair desta tela.");
    }

    String installInterrupted() {
        return t("The install was interrupted before Android finished it.",
                "A instalação foi interrompida antes de o Android terminar.");
    }

    String installButton() {
        return t("Install Waze with Wazeology", "Instalar o Waze com Wazeology");
    }

    String updateButton() {
        return t("Update Waze with Wazeology", "Atualizar o Waze com Wazeology");
    }

    String reinstallButton() {
        return t("Install again", "Instalar de novo");
    }

    String confirmButton() {
        return t("Continue installing", "Continuar a instalação");
    }

    String restartButton() {
        return t("Start the install again", "Começar a instalação de novo");
    }

    String openWazeology() {
        return t("Open Wazeology", "Abrir o Wazeology");
    }

    String allowInstallsTitle() {
        return t("Allow installs", "Permitir instalações");
    }

    String allowInstallsBody() {
        return t("Android needs your OK before this app can install Waze. Turn on \"Allow from this "
                        + "source\", then come back: the install continues on its own.",
                "O Android precisa da sua permissão para este app instalar o Waze. Ative \"Permitir "
                        + "desta fonte\" e depois volte: a instalação continua sozinha.");
    }

    String openSettings() {
        return t("Open settings", "Abrir configurações");
    }

    String notNow() {
        return t("Not now", "Agora não");
    }

    String meteredTitle() {
        return t("You're on mobile data", "Você está usando dados móveis");
    }

    String meteredBody(long remaining) {
        return t("Waze is about " + mb(remaining) + ". Download it now, or connect to Wi-Fi first.",
                "O Waze tem cerca de " + mb(remaining) + ". Baixe agora ou conecte-se ao Wi-Fi antes.");
    }

    String downloadAnyway() {
        return t("Download now", "Baixar agora");
    }

    // --- more ---

    String moreTitle() {
        return t("More", "Mais");
    }

    String storageUsed(long bytes) {
        return t("Downloaded files use " + mb(bytes) + " on this phone.",
                "Os arquivos baixados ocupam " + mb(bytes) + " neste celular.");
    }

    String storageNone() {
        return t("No downloaded files on this phone.", "Nenhum arquivo baixado neste celular.");
    }

    String deleteFiles() {
        return t("Delete downloaded files", "Apagar arquivos baixados");
    }

    String deleteConfirmTitle() {
        return t("Delete downloaded files?", "Apagar os arquivos baixados?");
    }

    String deleteConfirmBody() {
        return t("Frees the space used by the downloaded Waze and the prepared copy. Waze with "
                        + "Wazeology stays installed. Preparing again downloads Waze again.",
                "Libera o espaço do Waze baixado e da cópia preparada. O Waze com Wazeology continua "
                        + "instalado. Preparar de novo baixa o Waze de novo.");
    }

    String delete() {
        return t("Delete", "Apagar");
    }

    String exportButton() {
        return t("Save files for another phone", "Salvar arquivos para outro celular");
    }

    String exportHint() {
        return t("Saves the prepared files to a folder. The easiest way to set up another phone is to "
                        + "install Wazeology Installer there.",
                "Salva os arquivos preparados numa pasta. O jeito mais fácil de configurar outro "
                        + "celular é instalar o Wazeology Installer nele.");
    }

    String exportFolder() {
        return "Waze with Wazeology " + V;
    }

    String exportReadme() {
        return t("Waze " + V + " with Wazeology (unofficial), prepared by Wazeology Installer.\n\n"
                        + "Install all the .apk files in this folder together, in one go, with an app "
                        + "that can install several .apk files at once (for example SAI). Remove any "
                        + "other Waze first.\n"
                        + "The simpler route is to install Wazeology Installer on the other phone.\n",
                "Waze " + V + " com Wazeology (não oficial), preparado pelo Wazeology Installer.\n\n"
                        + "Instale todos os arquivos .apk desta pasta juntos, de uma vez, com um app que "
                        + "instale vários arquivos .apk ao mesmo tempo (por exemplo, o SAI). Remova "
                        + "qualquer outro Waze antes.\n"
                        + "O caminho mais simples é instalar o Wazeology Installer no outro celular.\n");
    }

    String showDetails() {
        return t("Show details", "Mostrar detalhes");
    }

    String hideDetails() {
        return t("Hide details", "Ocultar detalhes");
    }

    String copy() {
        return t("Copy", "Copiar");
    }

    String copied() {
        return t("Details copied", "Detalhes copiados");
    }

    String dismiss() {
        return t("Dismiss", "Fechar");
    }

    // --- progress ---

    String phaseTitle(JobState.Kind kind, JobState.Phase phase) {
        if (phase == null) {
            return t("Starting…", "Iniciando…");
        }
        switch (phase) {
            case LOOKUP:
                return t("Step 1 of 2: Finding Waze " + V, "Etapa 1 de 2: Procurando o Waze " + V);
            case DOWNLOAD:
                return t("Step 1 of 2: Downloading Waze", "Etapa 1 de 2: Baixando o Waze");
            case WAIT_NETWORK:
                return t("Step 1 of 2: Waiting for internet", "Etapa 1 de 2: Aguardando internet");
            case VERIFY:
                return t("Step 1 of 2: Checking the download", "Etapa 1 de 2: Conferindo o download");
            case COPY:
                return kind == JobState.Kind.EXPORT
                        ? t("Saving files", "Salvando arquivos")
                        : t("Step 1 of 2: Copying your files", "Etapa 1 de 2: Copiando seus arquivos");
            case UNPACK:
            case CHECK:
                return kind == JobState.Kind.PICK
                        ? t("Step 1 of 2: Checking your files", "Etapa 1 de 2: Conferindo seus arquivos")
                        : t("Step 2 of 2: Opening Waze", "Etapa 2 de 2: Abrindo o Waze");
            case GRAFT:
                return t("Step 2 of 2: Adding Wazeology", "Etapa 2 de 2: Adicionando o Wazeology");
            case SIGN:
                return t("Step 2 of 2: Sealing the app for Android",
                        "Etapa 2 de 2: Preparando o app para o Android");
            case FINALIZE:
                return t("Step 2 of 2: Finishing up", "Etapa 2 de 2: Finalizando");
            case WRITE:
                return t("Handing Waze to Android", "Entregando o Waze ao Android");
            default:
                return "";
        }
    }

    String phaseDetail(JobState.Snapshot s) {
        if (s.phase == JobState.Phase.WAIT_NETWORK) {
            return t("No connection. The download continues on its own when you're back online.",
                    "Sem conexão. O download continua sozinho quando a internet voltar.");
        }
        if (s.phase == JobState.Phase.LOOKUP) {
            return t("Contacting APKPure…", "Conectando ao APKPure…");
        }
        if (s.phase == JobState.Phase.DOWNLOAD || s.phase == JobState.Phase.COPY) {
            StringBuilder sb = new StringBuilder();
            sb.append(s.total > 0 ? mb(s.done) + " / " + mb(s.total) : mb(s.done));
            if (s.rate > 0) {
                sb.append(" · ").append(mbRate(s.rate));
                if (s.total > s.done) {
                    sb.append(" · ").append(eta((s.total - s.done) / Math.max(1, s.rate)));
                }
            }
            return sb.toString();
        }
        int pct = s.percent();
        return pct >= 0 ? pct + "%" : "";
    }

    String notifTitle(JobState.Kind kind) {
        if (kind == null) {
            return appName();
        }
        switch (kind) {
            case INSTALL:
                return t("Installing Waze with Wazeology", "Instalando o Waze com Wazeology");
            case EXPORT:
                return t("Saving files", "Salvando arquivos");
            case CLEAR:
                return t("Deleting downloaded files", "Apagando arquivos baixados");
            default:
                return t("Preparing Waze with Wazeology", "Preparando o Waze com Wazeology");
        }
    }

    String confirmNotifTitle(boolean uninstall) {
        return uninstall ? t("Confirm removing Waze", "Confirme a remoção do Waze")
                : t("Confirm the install", "Confirme a instalação");
    }

    String confirmNotifBody() {
        return t("Tap to open Android's confirmation.", "Toque para abrir a confirmação do Android.");
    }

    String readyNotifTitle() {
        return t("Waze with Wazeology is ready to install", "O Waze com Wazeology está pronto para instalar");
    }

    String tapToOpen() {
        return t("Tap to open Wazeology Installer.", "Toque para abrir o Wazeology Installer.");
    }

    String channelProgress() {
        return t("Progress", "Progresso");
    }

    String channelAlerts() {
        return t("Needs your attention", "Precisa da sua atenção");
    }

    // --- outcomes ---

    String title(Outcome p) {
        switch (p) {
            case PREPARED:
                return readyNotifTitle();
            case INSTALLED:
                return t("Waze with Wazeology is installed", "O Waze com Wazeology está instalado");
            case UNINSTALLED:
                return t("Waze removed", "Waze removido");
            case EXPORTED:
                return t("Files saved", "Arquivos salvos");
            case INTERRUPTED:
                return t("Setup was interrupted", "A preparação foi interrompida");
            case NETWORK:
                return t("Couldn't download Waze", "Não foi possível baixar o Waze");
            case SERVER:
                return t("APKPure isn't responding", "O APKPure não está respondendo");
            case NOT_LISTED:
                return t("APKPure no longer offers Waze " + V, "O APKPure não oferece mais o Waze " + V);
            case NO_MATCHING_DOWNLOAD:
                return t("No download fits this phone", "Nenhum download serve para este celular");
            case CORRUPT_DOWNLOAD:
                return t("The download was damaged", "O download veio com defeito");
            case NOT_WAZE:
                return t("That isn't Waze", "Isso não é o Waze");
            case WRONG_VERSION:
                return t("That's a different Waze version", "Essa é outra versão do Waze");
            case INCOMPLETE_FILES:
                return t("Those files aren't a complete Waze", "Esses arquivos não são um Waze completo");
            case MISSING_NATIVE:
                return t("A part of Waze is missing", "Falta uma parte do Waze");
            case MODIFIED_FILES:
                return t("That Waze was already modified", "Esse Waze já foi modificado");
            case STORAGE:
                return t("Not enough free space", "Espaço livre insuficiente");
            case INTERNAL:
                return t("Something went wrong", "Algo deu errado");
            case EXPORT_FAILED:
                return t("Couldn't save the files", "Não foi possível salvar os arquivos");
            case NEED_INSTALL_PERMISSION:
                return t("Installs aren't allowed yet", "As instalações ainda não foram permitidas");
            case INSTALL_CANCELLED:
                return t("Install canceled", "Instalação cancelada");
            case PLAY_PROTECT:
                return t("Play Protect stopped the install", "O Play Protect bloqueou a instalação");
            case INSTALL_BLOCKED:
                return t("Android blocked the install", "O Android bloqueou a instalação");
            case REMOVE_CURRENT:
                return t("Another Waze is in the way", "Outro Waze está atrapalhando");
            case NEWER_INSTALLED:
                return t("The Waze on this phone is newer", "O Waze deste celular é mais novo");
            case DAMAGED_BUILD:
                return t("The prepared files are damaged", "Os arquivos preparados estão com defeito");
            case MISSING_PART:
                return t("Part of Waze is missing", "Falta uma parte do Waze");
            case WRONG_DEVICE:
                return t("This Waze doesn't fit this phone", "Este Waze não serve para este celular");
            case ANDROID_TOO_OLD:
                return t("Android is too old for this Waze", "O Android é antigo demais para este Waze");
            case RESTRICTED:
                return t("Installs are restricted on this phone", "As instalações estão restritas neste celular");
            case INSTALL_FAILED:
                return t("The install didn't finish", "A instalação não terminou");
            case NO_CONFIRM:
                return t("Android didn't show the install window", "O Android não mostrou a janela de instalação");
            case UNINSTALL_CANCELLED:
                return t("Waze was not removed", "O Waze não foi removido");
            case UNINSTALL_FAILED:
                return t("Couldn't remove Waze", "Não foi possível remover o Waze");
            default:
                return p.name();
        }
    }

    String body(Outcome p, long neededBytes) {
        switch (p) {
            case PREPARED:
                return tapToOpen();
            case INSTALLED:
                return t("Open Wazeology to pair your motorcycle.", "Abra o Wazeology para parear sua moto.");
            case UNINSTALLED:
                return t("You can install Waze with Wazeology now.",
                        "Agora você pode instalar o Waze com Wazeology.");
            case EXPORTED:
                return t("The folder \"" + exportFolder() + "\" has everything needed.",
                        "A pasta \"" + exportFolder() + "\" tem tudo o que é preciso.");
            case INTERRUPTED:
                return t("The app was closed while working. Start again below: anything already done "
                                + "is kept.",
                        "O app foi fechado no meio do trabalho. Comece de novo abaixo: o que já foi feito "
                                + "fica guardado.");
            case NETWORK:
                return t("Check your internet connection and try again. What was already downloaded is kept.",
                        "Verifique sua conexão e tente de novo. O que já foi baixado fica guardado.");
            case SERVER:
                return t("Try again in a few minutes, or use Waze files you already have.",
                        "Tente de novo em alguns minutos ou use arquivos do Waze que você já tem.");
            case NOT_LISTED:
            case NO_MATCHING_DOWNLOAD:
                return t("Get the Waze " + V + " .xapk (or all its .apk files) from another source and "
                                + "use \"Use Waze files I already have\".",
                        "Consiga o .xapk do Waze " + V + " (ou todos os arquivos .apk dele) em outra fonte "
                                + "e use \"Usar arquivos do Waze que já tenho\".");
            case CORRUPT_DOWNLOAD:
                return t("It didn't match what APKPure published. Try again to download a fresh copy.",
                        "Ele não bateu com o que o APKPure publicou. Tente de novo para baixar uma cópia nova.");
            case NOT_WAZE:
                return t("Pick the Waze .xapk, or all of the Waze .apk files.",
                        "Escolha o .xapk do Waze ou todos os arquivos .apk do Waze.");
            case WRONG_VERSION:
                return t("Wazeology needs exactly Waze " + V + ". Let the app download it instead.",
                        "O Wazeology precisa exatamente do Waze " + V + ". Deixe o app baixá-lo.");
            case INCOMPLETE_FILES:
                return t("Pick the whole .xapk, or all of the Waze .apk files at once.",
                        "Escolha o .xapk inteiro ou todos os arquivos .apk do Waze de uma vez.");
            case MISSING_NATIVE:
                return t("Your files lack the part for this phone's processor (usually config.arm64_v8a.apk). "
                                + "Pick it along with the rest.",
                        "Seus arquivos não têm a parte para o processador deste celular (normalmente "
                                + "config.arm64_v8a.apk). Escolha-a junto com o resto.");
            case MODIFIED_FILES:
                return t("Wazeology needs an unmodified Waze. Let the app download it instead.",
                        "O Wazeology precisa de um Waze sem modificações. Deixe o app baixá-lo.");
            case STORAGE:
                return neededBytes > 0
                        ? t("Free up about " + mb(neededBytes) + " and try again.",
                                "Libere cerca de " + mb(neededBytes) + " e tente de novo.")
                        : t("Free up some space and try again.", "Libere espaço e tente de novo.");
            case INTERNAL:
                return t("Nothing was installed. Try again; if it keeps happening, copy the details and "
                                + "report it.",
                        "Nada foi instalado. Tente de novo; se continuar, copie os detalhes e relate o problema.");
            case EXPORT_FAILED:
                return t("Pick another folder and try again.", "Escolha outra pasta e tente de novo.");
            case NEED_INSTALL_PERMISSION:
                return allowInstallsBody();
            case INSTALL_CANCELLED:
                return t("Nothing was changed. Install again whenever you're ready.",
                        "Nada foi alterado. Instale de novo quando quiser.");
            case PLAY_PROTECT:
                return t("Android doesn't recognize this app. Try again, and in the warning tap More details, "
                                + "then Install anyway.",
                        "O Android não reconhece este app. Tente de novo e, no aviso, toque em Mais detalhes "
                                + "e depois em Instalar mesmo assim.");
            case INSTALL_BLOCKED:
                return t("A security setting stopped it. On Samsung phones, turn off Auto Blocker "
                                + "(Settings > Security and privacy), then try again.",
                        "Uma configuração de segurança impediu. Em celulares Samsung, desligue o Bloqueador "
                                + "automático (Configurações > Segurança e privacidade) e tente de novo.");
            case REMOVE_CURRENT:
                return t("Android allows only one Waze. Remove the current one, then install again.",
                        "O Android só permite um Waze. Remova o atual e instale de novo.");
            case NEWER_INSTALLED:
                return t("Wazeology works with Waze " + V + ", and Android won't replace a newer Waze with "
                                + "it. Remove the current Waze, then install again.",
                        "O Wazeology funciona com o Waze " + V + ", e o Android não troca um Waze mais novo "
                                + "por ele. Remova o Waze atual e instale de novo.");
            case DAMAGED_BUILD:
                return t("Prepare them again; the download is reused.",
                        "Prepare de novo; o download é reaproveitado.");
            case MISSING_PART:
            case WRONG_DEVICE:
                return t("Download Waze again so the app gets the right parts for this phone.",
                        "Baixe o Waze de novo para o app pegar as partes certas para este celular.");
            case ANDROID_TOO_OLD:
                return t("Waze " + V + " needs Android 12L or newer.",
                        "O Waze " + V + " precisa do Android 12L ou mais novo.");
            case RESTRICTED:
                return t("A work profile, parental controls or a phone setting blocks app installs. On "
                                + "Xiaomi phones, turn on \"Install via USB\" in Developer options.",
                        "Um perfil de trabalho, o controle dos pais ou uma configuração do celular bloqueia "
                                + "instalações. Em celulares Xiaomi, ative \"Instalar via USB\" nas Opções "
                                + "do desenvolvedor.");
            case INSTALL_FAILED:
            case NO_CONFIRM:
                return t("Try the install again.", "Tente instalar de novo.");
            case UNINSTALL_CANCELLED:
                return t("The current Waze is still installed.", "O Waze atual continua instalado.");
            case UNINSTALL_FAILED:
                return t("Try again, or remove Waze from Android's Settings > Apps.",
                        "Tente de novo ou remova o Waze em Configurações > Apps do Android.");
            default:
                return "";
        }
    }

    String action(Outcome.Action a) {
        switch (a) {
            case RETRY_PREPARE:
                return t("Try again", "Tentar de novo");
            case PICK_FILES:
                return pickFiles();
            case INSTALL:
                return t("Install again", "Instalar de novo");
            case UNINSTALL_WAZE:
                return removeWaze();
            case FREE_SPACE:
                return t("Free up space", "Liberar espaço");
            case ALLOW_INSTALLS:
                return allowInstallsTitle();
            case OPEN_WAZEOLOGY:
                return openWazeology();
            case REBUILD:
                return t("Prepare again", "Preparar de novo");
            case REDOWNLOAD:
                return t("Download Waze", "Baixar o Waze");
            case OPEN_SECURITY_SETTINGS:
                return openSettings();
            case EXPORT:
                return t("Choose another folder", "Escolher outra pasta");
            default:
                return "";
        }
    }

    // --- formatting ---

    String mb(long bytes) {
        double m = bytes / (1024.0 * 1024.0);
        return (m >= 10 ? String.format(Locale.US, "%.0f", m) : String.format(Locale.US, "%.1f", m))
                .replace('.', pt ? ',' : '.') + " MB";
    }

    private String mbRate(long bytesPerSec) {
        return mb(bytesPerSec) + "/s";
    }

    private String eta(long seconds) {
        if (seconds < 60) {
            return t("about " + Math.max(1, seconds) + " s left", "faltam cerca de " + Math.max(1, seconds) + " s");
        }
        long min = (seconds + 30) / 60;
        return t("about " + min + " min left", "faltam cerca de " + min + " min");
    }

    @SuppressWarnings("deprecation")
    private static Locale localeOf(Context context) {
        Configuration config = context.getResources().getConfiguration();
        Locale loc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? config.getLocales().get(0)
                : config.locale;
        return loc != null ? loc : Locale.getDefault();
    }
}
