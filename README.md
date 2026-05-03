# Java P2P Encrypted Chat

A command-line peer-to-peer chat system written in Java using only standard library APIs. Each node acts as both a TCP server and a TCP client, establishes an RSA/AES encrypted session with peers, shares DNS-like username records, and supports peer discovery, heartbeats, latency reporting, and benchmarking.

## Features

- Peer-to-peer nodes over TCP sockets
- CLI only, no GUI
- RSA public-key exchange during connection setup
- AES encrypted session messages after handshake
- Bidirectional full-duplex communication
- Local DNS-like cache: `username -> host:port`
- Distributed peer discovery through DNS queries, peer lists, and user announcements
- Automatic connection when sending to a known user
- Automatic startup connection attempts to known peers
- Heartbeat fault tolerance using `PING` and `PONG`
- Peer timeout detection and cleanup
- Message latency reporting
- Benchmark command for measuring average message latency
- Debug mode with default `userA` and `userB` records

## Requirements

- Java 8 or newer
- PowerShell on Windows for `run.ps1`
- No external dependencies

## Project Structure

```text
.
├── README.md
├── run.ps1
├── src
│   ├── AesCipher.java
│   ├── BenchmarkState.java
│   ├── ChatNode.java
│   ├── ConnectionHandler.java
│   ├── ConnectionManager.java
│   ├── ConsolePrinter.java
│   ├── CryptoManager.java
│   ├── DnsService.java
│   ├── JsonUtil.java
│   ├── Main.java
│   └── PeerAddress.java
└── build
```

## Quick Start

Open two PowerShell terminals in the project directory.

Terminal 1:

```powershell
.\run.ps1 userA 5000
```

Terminal 2:

```powershell
.\run.ps1 userB 5001
```

Debug mode is enabled by default, so `userA` and `userB` are preloaded:

```text
userA -> localhost:5000
userB -> localhost:5001
```

Send a message:

```text
send userB hello from userA
```

Reply from the other node:

```text
send userA hello back
```

## Running Without Debug Mode

```powershell
.\run.ps1 alice 6000 -NoDebug
```

Manual registration:

```text
register bob localhost 6001
send bob hello
```

## Manual Compile and Run

Compile:

```powershell
javac --release 8 -d build src\*.java
```

Run:

```powershell
java -cp build Main userA 5000 --debug
```

Custom cache file:

```powershell
java -cp build Main userA 5000 --debug dns-cache-userA.properties
```

## CLI Commands

```text
send <username> <message>
search <username>
search all
benchmark <username> [count]
heartbeat [status|on [5-99]|off|every <5-99>]
peers
dns
clear / cls
help
exit
```

Optional commands:

```text
register <username> <host> <port>
resolve <username>
connect <username>
```

## Command Details

### `send <username> <message>`

Sends an encrypted message to a peer. If the peer is known in DNS but not connected, the node attempts to connect automatically.

Example:

```text
send userB hello
```

Received messages include latency:

```text
[MSG][userA]: hello (8 ms)
```

The sender receives an ACK:

```text
[INFO] Delivered to userB in 8 ms
```

### `search <username>`

Checks local DNS first. If the user is not found locally, the node broadcasts a TTL-limited DNS query to connected peers.

```text
search userB
```

### `search all`

Requests known DNS tables from connected peers and merges the results. This shows the full network view, including discovered peers that may not currently be connected.

```text
search all
```

### `dns`

Shows a cleaned local DNS view:

- currently connected peers
- manually registered peers

Discovered peers that are not connected remain stored internally for discovery and auto-connect, but are hidden from this command.

### `peers`

Shows active secure peer sessions.

### `benchmark <username> [count]`

Sends benchmark messages and reports total time and average latency. The default count is `100`.

```text
benchmark userB 100
```

Output:

```text
[INFO] Benchmark started: 100 messages to userB
[INFO] Benchmark complete: received 100/100 ACKs
[INFO] Benchmark total time: 1234 ms
[INFO] Benchmark average latency: 4 ms
```

### `heartbeat`

Heartbeats always run in the background. Logging is rate-limited for cleaner demos.

```text
heartbeat status
heartbeat on
heartbeat on 10
heartbeat every 25
heartbeat off
```

The interval is clamped to the range `5-99`.

## Security Flow

When two nodes connect:

1. Both sides exchange plaintext `hello` messages containing username and RSA public key.
2. The outgoing side generates an AES session key.
3. The AES key is encrypted with the peer RSA public key.
4. The receiving side decrypts the AES key with its RSA private key.
5. All subsequent messages are AES encrypted.

Successful setup logs:

```text
[SECURE] AES session key established with userB
```

## Distributed Discovery

Nodes learn about peers through:

- debug preload
- manual `register`
- `USER_ANNOUNCE`
- `PEER_LIST`
- `DNS_QUERY`
- `DNS_RESPONSE`

When a node learns a peer, it merges the record into local DNS and avoids duplicate peer entries by `host:port` during peer-list exchange.

Example discovery log:

```text
[DNS] Learned userC -> localhost:5002
```

## Fault Tolerance

Each node sends heartbeat `PING` messages every 3 seconds. Peers respond with `PONG`. If no `PONG` is seen for 10 seconds, the peer is considered unreachable and the session is closed.

```text
[WARN] Peer userB is offline or unreachable
```

Heartbeat logs are suppressed by default after the first event per peer. Use `heartbeat on 10` to show every 10th event.

## Console Behavior

Logs never print the prompt. The prompt is printed only by the main input loop when the system is ready for a command:

```text
> 
```

Long-running commands such as `benchmark` complete before the next prompt is displayed.

## Notes

- DNS cache files are stored as `.properties` files in the project directory.
- This project is intended for local and educational use.
- The JSON support is intentionally minimal and implemented with standard Java only.
- The cryptography uses standard Java cryptographic APIs only.

