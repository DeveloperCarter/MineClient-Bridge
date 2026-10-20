# 1.1.8

1.1.6 and 1.1.7 were never released. 1.1.6 existed only in the source
repository, and its GUI pointer fix ships here; the 1.1.7 number was not used.
These notes cover every change since 1.1.5.

## GUI pointer position (from 1.1.6)

- GUI mouse actions update Minecraft's internal pointer position as well as
  dispatching the screen event. Hover tooltips and ability-wheel selection now
  use the requested GUI coordinates.
- Input remains process-local in isolated sessions. The fix does not move the
  operating-system pointer, capture native input or change window focus.

## Placeholder input callbacks in isolated sessions

An isolated session skipped Minecraft's GLFW keyboard and mouse callback
registration, which left those callback slots of the window empty. A mod that
wraps the existing callbacks during startup and requires a previous
cursor-position callback, as KotlinMCUI does, crashed the client at startup.

The window now holds do-nothing placeholder callbacks instead, so such wrappers
have something to chain to. Physical input that reaches a placeholder is
discarded, and the native cursor, focus and clipboard stay untouched.
`Window.close` detaches and frees the placeholders without freeing callbacks
that other mods installed on top of them. Normal foreground sessions are
unchanged.

Bridge input is delivered through Minecraft's own handlers, not through these
callbacks. A mod interface that reads only the wrapped device callbacks
therefore does not see Bridge input; this release does not add a way to drive
such an interface.

## Ixeris

When Ixeris is active, Minecraft runs on Ixeris's render thread and Bridge
requests are queued on Ixeris's render queue. That queue runs at the end of
every frame, including the frames drawn while a world loads or disconnects.
Without Ixeris, or with Ixeris installed but not active, for example because
it is turned off for the platform in its configuration, requests use
Minecraft's own task queue as before. If the Ixeris classes the Bridge uses
cannot be bound, for example after an Ixeris update renames them, it falls back
to Minecraft's queue and logs the reason.

On every route a request runs only after checking that it is on Minecraft's
render thread. The client log names the route when it is first used:
`VANILLA`, `IXERIS`, `VANILLA_IXERIS_INACTIVE` or `VANILLA_IXERIS_UNAVAILABLE`.

## MCP adapter

- `minecraft_client_launch` reads the ZIP directory of every JAR in the
  prepared `mods` folder and refused archives that keep that directory in
  ZIP64 records, which mods with more than 65,535 entries need, so such a root
  could not be launched. ZIP64 directories are now read with the existing
  bounds kept; multi-disk archives are still refused.
- The server reports version 1.1.8.

## Upgrade notes

- Replace any older Bridge JAR. The launch check still accepts Bridge JARs from
  1.1.3 on, but before 1.1.8 an isolated client crashes at startup when
  KotlinMCUI is installed.
- The mod is still client-only. No server installation is needed.
