#!/usr/bin/python3
import logging
import os
import subprocess
import time
from urllib.error import URLError
from urllib.request import urlopen

from evdev import InputDevice, ecodes


ROUTES = {
    ecodes.KEY_VOLUMEUP: "/VolumeUp",
    ecodes.KEY_VOLUMEDOWN: "/VolumeDown",
    ecodes.KEY_MUTE: "/On",  # Mantiene il tasto di accensione della configurazione esistente.
}
VOLUME_KEYS = {ecodes.KEY_VOLUMEUP, ecodes.KEY_VOLUMEDOWN}


def main():
    logging.basicConfig(level=logging.INFO)
    device = InputDevice("/dev/input/event0")
    subprocess.run(["ir-keytable", "-c", "-w", "/opt/BoseIr/my_samsung", "-D", "250", "-P", "150"], check=True)
    tv_host = os.environ.get("BOSE_TV_HOST")
    last_volume = 0.0
    for event in device.read_loop():
        if event.type == ecodes.EV_MSC and event.code == ecodes.MSC_SCAN and event.value == 0x70702:
            if tv_host:
                subprocess.Popen(["python3", "/opt/BoseIr/pylips.py", "--host", tv_host, "--command", "standby"])
            continue
        if event.type != ecodes.EV_KEY or event.code not in ROUTES:
            continue
        if event.value == 2:
            # Ignora le ripetizioni vecchie mentre la seriale sta completando il comando precedente.
            if (event.code not in VOLUME_KEYS or time.time() - event.timestamp() > 0.25
                    or time.monotonic() - last_volume < 0.18):
                continue
        elif event.value != 1:
            continue
        if event.code in VOLUME_KEYS:
            last_volume = time.monotonic()
        try:
            with urlopen("http://127.0.0.1:5000" + ROUTES[event.code], timeout=3) as response:
                response.read()
        except (URLError, OSError) as error:
            logging.warning("Comando Bose fallito: %s", error)


if __name__ == "__main__":
    main()
