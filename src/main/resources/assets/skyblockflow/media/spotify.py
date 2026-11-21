import base64
import io
import json
import os
import time
from urllib.parse import urlsplit
from urllib.request import urlopen
try:
    from gi.repository import Gio, GLib
    bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
except Exception:
    bus = None

def discord_count():
    # Read X11 window metadata in-process; works with XWayland clients too.
    import gi, re
    gi.require_version("Gdk", "3.0")
    gi.require_version("Wnck", "3.0")
    from gi.repository import Gdk, Wnck
    display = Gdk.Display.get_default()
    if not display:
        return -1
    screen = Wnck.Screen.get_default()
    if not screen:
        return -1
    ctx=GLib.MainContext.default()
    while ctx.pending(): ctx.iteration(False)
    screen.force_update()
    for win in screen.get_windows():
        if (win.get_class_group_name() or "").lower() == "discord":
            match = re.match(r"^\s*\((\d+)\+?\)", win.get_name() or "")
            if match: return int(match[1])
    return -1
def call(name, path, interface, method, args=None):
    return bus.call_sync(name, path, interface, method, args, None,
                         Gio.DBusCallFlags.NONE, 2500, None).unpack()[0]

def cover(url):
    # Spotify's MPRIS supplies a local file or a Spotify-hosted cover.
    from PIL import Image
    from urllib.request import url2pathname
    u = urlsplit(url)
    if u.scheme == "file" and not u.netloc:
        source = open(url2pathname(u.path), "rb")
    elif u.scheme == "https" and u.hostname in {"i.scdn.co", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com"}:
        source = urlopen(url, timeout=3)
    else:
        return ""
    with source:
        data = source.read(4000001)
    if len(data) > 4000000:
        return ""
    with Image.open(io.BytesIO(data)) as img:
        if img.width * img.height > 16000000:
            return ""
        img.thumbnail((64,64))
        out = io.BytesIO()
        img.convert("RGB").save(out, "PNG")
        return base64.b64encode(out.getvalue()).decode("ascii")

key, art = None, ""
while True:
    result = {}
    try:
        names = [] if bus is None or os.getenv("SKYBLOCKFLOW_SPOTIFY")=="0" else call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames")
        players = [n for n in names if n.lower().startswith("org.mpris.mediaplayer2.spotify")]
        for name in players:
            props = call(name, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties", "GetAll",
                         GLib.Variant("(s)", ("org.mpris.MediaPlayer2.Player",)))
            meta = props.get("Metadata", {})
            url = meta.get("mpris:artUrl", "")
            if url != key:
                try: art = cover(url)
                except Exception: art = ""
                key = url
            result = dict(title=meta.get("xesam:title", ""), artist=", ".join(meta.get("xesam:artist", [])),
                          album=meta.get("xesam:album", ""), position=props.get("Position", 0)//1000,
                          duration=meta.get("mpris:length", 0)//1000, playing=props.get("PlaybackStatus")=="Playing", art=art)
            if result["playing"]: break
    except Exception:
        pass
    if os.getenv("SKYBLOCKFLOW_DISCORD") == "1":
        try: result["discord"] = discord_count()
        except Exception: pass
    print(json.dumps(result), flush=True)
    if os.getenv("SKYBLOCKFLOW_MEDIA_ONCE"): break
    time.sleep(5)
