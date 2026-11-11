from flask import Flask, jsonify
from serial import Serial
# import time
import os
import time
from threading import Lock
from serial import SerialException


app = Flask(__name__)
@app.route("/")

def home():
    return "Hello, BoseRemote!"

@app.route('/VolumeUpTest')
def volumeUpTest():
    ResponseTxt = SendBoseCommand(b'\x0b\x00\x01\x04\x03\x01\x2c\x01\x00\x00\x21','Volume',8)
    return ResponseTxt


@app.route('/VolumeUp')
def volumeUp():
    ResponseTxt = SendBoseCommand(b'\x0b\x00\x01\x04\x03\x01\x2c\x01\x00\x00\x21','Volume',8)
    return ResponseTxt

@app.route('/VolumeDown')
def volumeDown():
    ResponseTxt = SendBoseCommand(b'\x0b\x00\x01\x04\x02\x01\x2c\x01\x00\x00\x20','Volume',8)
    return ResponseTxt

#@app.route('/OnOff')
#def OnOff():
#    ResponseTxt = SendBoseCommand(b'\x0b\x00\x01\x04\x4c\x01\x2c\x01\x00\x00\x6e','OnOff',8)
#    return "Power On Off" + ResponseTxt


@app.route('/On')
def On():
    ResponseTxt = SendBoseCommand(b'\x0b\x00\x01\x04\x8c\x01\x2c\x01\x00\x00\xAE','Bose On',8)
    return ResponseTxt

@app.route('/Off')
def Off():
    ResponseTxt = SendBoseCommand(b'\x0b\x00\x01\x04\xcc\x01\x2c\x01\x00\x00\xEE','Bose Off',8)
    return ResponseTxt



@app.route('/Mute')
def mute():
    ResponseTxt = SendBoseCommand(b'\x0b\x00\x01\x04\x01\x01\x2c\x01\x00\x00\x23','Mute',8)
    return "Mute" + ResponseTxt

#@app.route('/SysRdy')
#def SysRdy():
#    ResponseTxt = SendBoseCommand(b'\x07\x00\x01\x1d\x00\x00\x1b','SysRdy',5)
#    return "SysRdy" + ResponseTxt


#@app.route('/switchHDMI')
#def switchHDMI():

#    currentHDMI=22

#    if (currentHDMI==1):
	# Switch to HDMI2 0b 00 01 04 0A 01 2c 01 00 00 28
#    	ResponseTxt = SendBoseCommand(b'\x0b\x00\x01\x04\x0A\x01\x2c\x01\x00\x00\x28','HDMI2',8)
#	currentHDMI=1
#    if (currentHDMI==2):
	# Switch to HDMI3 0b 00 01 04 0B 01 2c 01 00 00 29
#	ResponseTxt = SendBoseCommand(b'\x0b\x00\x01\x04\x0B\x01\x2c\x01\x00\x00\x29','HDMI3',8)
#	currentHDMI=3
#    if (currentHDMI==3):
	# Switch to HDMI1 0b 00 01 04 09 01 2c 01 00 00 2B
#	ResponseTxt = SendBoseCommand(b'\x0b\x00\x01\x04\x09\x01\x2c\x01\x00\x00\x2B','HDMI1',8)
#	currentHDMI=1
#    return "Current HDMI: " + GetCurrentSource()


def GetCurrentSource():
   response_txt =  SendBoseCommand(b'\x08\x00\x01\x0a\x00\x18\x18\x03','HDMI_STATUS',8)
   print(response_txt)
   return response_txt


PORT = os.environ.get("BOSE_SERIAL_PORT", "/dev/USB2SERIAL")
VOLUME_QUERY = bytes.fromhex("07 00 01 15 00 1e 0d")
LOCK = Lock()
VOLUME_CACHE_SECONDS = 0.65
volume_cache = (None, 0.0)


class BoseProtocolError(Exception):
    pass


def transaction(port, command, response_opcode=None):
    app.logger.debug("Bose TX %s", command.hex())
    port.write(command)
    deadline = time.monotonic() + 1.5
    buffer = bytearray()
    response = None
    error = None
    while time.monotonic() < deadline:
        chunk = port.read(min(256, port.in_waiting) or 1)
        buffer.extend(chunk)
        while buffer:
            length = buffer[0]
            if length < 4:
                raise BoseProtocolError("invalid_frame_length")
            if len(buffer) < length:
                break
            frame = bytes(buffer[:length])
            del buffer[:length]
            app.logger.debug("Bose RX %s", frame.hex())
            status, opcode = frame[1], frame[2:4]
            if not status & 0x02 or status & 0x08:
                continue
            if status & 0x80:
                error = frame.hex()
            elif opcode == b"\x01\x00" and length == 4:
                if error:
                    raise BoseProtocolError("console_error: " + error)
                if response_opcode is not None and response is None:
                    raise BoseProtocolError("missing_response")
                time.sleep(0.05)
                return response
            elif opcode == response_opcode:
                response = frame[4:]
    raise BoseProtocolError("ready_timeout")


def read_volume(port):
    payload = transaction(port, VOLUME_QUERY, b"\x01\x15")
    if len(payload) != 1 or payload[0] > 100:
        raise BoseProtocolError("invalid_volume")
    global volume_cache
    volume_cache = (payload[0], time.monotonic())
    return payload[0]


@app.route("/api/state")
def state():
    with LOCK:
        volume, updated_at = volume_cache
        if time.monotonic() - updated_at >= VOLUME_CACHE_SECONDS or volume is None:
            with Serial(PORT, 19200, timeout=0.05, write_timeout=0.5, exclusive=True) as port:
                volume = read_volume(port)
    response = jsonify(volume=volume)
    response.headers["Cache-Control"] = "no-store"
    return response


def SendBoseCommand(command, description, _response_length):
    global volume_cache
    with LOCK, Serial(PORT, 19200, timeout=0.05, write_timeout=0.5, exclusive=True) as port:
        if description == "HDMI_STATUS":
            return transaction(port, command, command[2:4]).hex()
        volume_cache = (None, 0.0)
        transaction(port, command)
        if description == "Volume":
            return f"Volume {read_volume(port)}"
    return description


@app.errorhandler(BoseProtocolError)
def protocol_error(error):
    app.logger.warning("Risposta Bose: %s", error)
    return jsonify(volume=None, error=str(error)), 502


@app.errorhandler(SerialException)
def serial_error(error):
    app.logger.warning("Seriale Bose: %s", error)
    return jsonify(volume=None, error="serial_unavailable"), 503
