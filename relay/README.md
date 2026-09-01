# cmux Tailnet Relay

Small macOS helper that forwards the existing cmux mobile framed protocol from
one Tailscale address to the local cmux Unix socket. It does not expose the
socket publicly and does not add a cloud relay.

## Run

Find the Mac's Tailscale address, then run:

```sh
python3 relay/cmux_relay.py --bind 100.x.y.z --port 58466
```

Point cmux Android's **Private network** connection at that Tailscale address
and port. The upstream cmux socket still validates each request's existing cmux
authentication envelope; the relay only provides a tailnet transport boundary.

The relay rejects wildcard, loopback, and ordinary LAN bind addresses. Keep the
port restricted to the Android device with Tailscale grants/ACLs. Do not use
router port forwarding or Tailscale Funnel.

## launchd

Run the relay under the same macOS user that owns cmux. A launchd plist should
invoke the script with an explicit Tailscale bind address and use
`KeepAlive=true`. Keep stdout/stderr in the user's log directory and stop the
job before changing cmux socket permissions.

## Scope

This relay is intentionally a raw framed-protocol bridge. cmux remains the
authentication and authorization boundary. Future protocol changes should be
covered by a relay compatibility test before updating the Android client.
