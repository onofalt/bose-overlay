# Bose Overlay per Google TV

Companion Android TV per Bose Lifestyle V35. Il Raspberry Pi legge il volume dalla seriale Bose e Google TV lo mostra sopra le altre app. Il companion invia l'accensione all'avvio e al risveglio e lo spegnimento quando lo Streamer entra in standby.

## Raspberry Pi

In `raspberry-pi/`:

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
BOSE_SERIAL_PORT=/dev/USB2SERIAL .venv/bin/gunicorn --workers 1 -b 0.0.0.0:5000 app:app
```

Indica la porta seriale effettiva e assegna all'utente del servizio il permesso di aprirla. Usa un solo worker per condividere lo stato e serializzare i comandi. Esporre l'API solo sulla rete locale fidata: gli endpoint non richiedono autenticazione.

`GET /api/state` restituisce un oggetto JSON con il volume. Restano disponibili `/VolumeUp`, `/VolumeDown`, `/On`, `/Off` e `/Mute`.

`BoseIr.py` è facoltativo. Richiede `ir-keytable`, un ricevitore configurato su `/dev/input/event0` e la keymap Samsung NEC inclusa in `raspberry-pi/my_samsung`. Copia la keymap in `/opt/BoseIr/my_samsung` prima di avviare `BoseIr.py` con un utente autorizzato a configurare e leggere il ricevitore. Usa Flask sulla porta 5000. Il tasto Samsung mute conserva il comportamento dell'impianto originale e accende il Bose. Il comando standby del televisore via `pylips.py` è facoltativo e usa `BOSE_TV_HOST`.

## Android TV

Servono un JDK, Android SDK Platform 35 e Android Build Tools 35.0.0. Dal repository:

```sh
export ANDROID_SDK_ROOT=/percorso/sdk
bash android/build.sh
adb install -r android/dist/Bose-Overlay.apk
```

Se i build tools sono in un'altra directory, impostare `ANDROID_BUILD_TOOLS`. Lo script genera una chiave di debug locale al primo avvio. Per aggiornare una build già installata serve la stessa chiave usata in precedenza.

Aprire Bose Overlay, consentire la visualizzazione sopra le altre app e inserire l'URL del Raspberry Pi, ad esempio `http://raspberrypi.local:5000`. Selezionare **Avvia collegamento al Raspberry**. Il pulsante di prova mostra la barra dopo cinque secondi. La sincronizzazione con lo standby richiede che il companion sia attivo.

Il logo è di Bose. Questo progetto è un companion indipendente.
