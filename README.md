# Distributed Systems Course Project

## Requirements

- Java 11 or newer
- Maven 3.9 or newer

The project uses Jackson Databind for JSON parsing and SQLite JDBC for persistence. Maven downloads both automatically from `pom.xml`.

## Build

```powershell
mvn clean test
```

If Maven is not on your `PATH`, run the Maven executable from its `bin` directory.

## Run nodes

There are 10 configured nodes. Node IDs `0` through `9` use ports `8000` through `8009`.
By default, nodes communicate with `localhost`. To run nodes across computers,
set `CHAT_PEERS` to the same comma-separated `host:port` list on every computer.

Build the project first:

```powershell
mvn package
```

Then open a separate terminal for each node and run:

```powershell
java -cp "target/classes;target/dependency/*" Node 0 8000
java -cp "target/classes;target/dependency/*" Node 1 8001
```

For example, if nodes 0-4 run on `192.168.1.10` and nodes 5-9 run on
`192.168.1.11`, configure every process with:

```powershell
$env:CHAT_PEERS = "192.168.1.10:8000,192.168.1.10:8001,192.168.1.10:8002,192.168.1.10:8003,192.168.1.10:8004,192.168.1.11:8005,192.168.1.11:8006,192.168.1.11:8007,192.168.1.11:8008,192.168.1.11:8009"
$env:CHAT_INTERNAL_SECRET = "use-the-same-secret-on-every-computer"
java -cp "target/classes;target/dependency/*" Node 0 8000
```

Replace the node ID and port for each process. Allow TCP ports `8000`-`8009`
through the firewalls, and make sure the computers can reach one another.

Repeat for IDs and ports `2 8002` through `9 8009`. Node `0` starts with the token, and node `9` is the initial coordinator. All nodes use the shared `chat.db` file by default so accounts and messages are available through every node.

When nodes run on separate computers, each computer has its own SQLite file.
Messages are sent between configured peers, but user accounts are not a shared
database. Create the same account on the node where you log in, or use a shared
database/authentication service for a real multi-computer deployment.

For a real deployment, set the same internal-node secret for every process:

```powershell
$env:CHAT_INTERNAL_SECRET = "replace-with-a-long-random-secret"
```

### Start all nodes from one PowerShell window

From the project directory, after running `mvn package`:

```powershell
$classpath = "target/classes;target/dependency/*"
0..9 | ForEach-Object {
	Start-Process java -ArgumentList "-cp `"$classpath`" Node $_ $(8000 + $_)"
}
```

Each node opens in a separate Java process. Stop them with:

```powershell
Get-Process java | Stop-Process
```

### Test health

```powershell
Invoke-RestMethod http://localhost:8000/api/health
```

Expected response:

```text
status
------
ALIVE
```

### Use the browser chat interface

Open [http://localhost:8000/](http://localhost:8000/) in a browser. Register an account, sign in, and use the chat screen to send messages. Messages are saved to SQLite and broadcast to the other running nodes.

The same workflow can be tested from PowerShell:

```powershell
$account = @{ username = 'alice'; password = 'password123' } | ConvertTo-Json -Compress
$login = Invoke-RestMethod http://localhost:8000/api/auth/register -Method Post -ContentType 'application/json' -Body $account
$headers = @{ Authorization = "Bearer $($login.token)" }
$message = @{ conversation_id = 1; text = 'Hello from node 0' } | ConvertTo-Json -Compress

Invoke-RestMethod http://localhost:8000/api/chat/send -Method Post -ContentType 'application/json' -Headers $headers -Body $message
Invoke-RestMethod 'http://localhost:8000/api/chat/messages?conversation_id=1' -Headers $headers
```

Create and list conversations with `/api/conversations`. All protected routes use `Authorization: Bearer <token>`.

### Test leader election

With all nodes running, stop node `9`:

```powershell
Get-Process java | Where-Object { $_.Id -eq (Get-NetTCPConnection -LocalPort 8009).OwningProcess } | Stop-Process
```

After the health-check interval, node `8` should become coordinator because it is the highest remaining node. Watch the node consoles for election messages.

### Test token passing

Request and inspect token-based mutual exclusion through the API:

```powershell
Invoke-RestMethod http://localhost:8000/api/mutex/request -Method Post -Headers $headers
Invoke-RestMethod http://localhost:8000/api/mutex/status -Headers $headers
```

The token ring requires all ten nodes to be running: `0 -> 1 -> ... -> 9 -> 0`.

## Application API

- `GET /` - browser chat interface.
- `POST /api/auth/register` and `POST /api/auth/login` - account and bearer-token authentication.
- `GET /api/conversations` and `POST /api/conversations` - conversation tracking.
- `POST /api/chat/send` - persist and broadcast an authenticated message.
- `GET /api/chat/messages?conversation_id=1` - retrieve stored messages.
- `POST /api/mutex/request` and `GET /api/mutex/status` - mutual-exclusion controls.

## Remaining improvements

1. Add membership management so users can be added to private conversations.
2. Add token recovery and election timeouts for failed nodes.
3. Add automated tests for clocks, authentication, message broadcast, election, and token passing.