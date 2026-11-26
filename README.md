IEC 60870-5-104 from Eclipse NeoSCADA
=====================================

Eclipse NeoSCADA's IEC 60870-5-104 client and server, as extracted by
[Syncron Tech](https://github.com/SyncronTech/neoscada-iec60870) from the NeoSCADA repository (see
[History](#history)), with fixes, IEC 62351-3 TLS, tests and an interoperability suite added in this fork.
The licence is the Eclipse Public License 1.0, as it was in NeoSCADA.

The package and module names are NeoSCADA's, unchanged, and so is the API apart from additions: code written
against Syncron Tech's 14.0.0 needs no change but for Guava's own between versions 18 and 33.

Using it
--------

From [JitPack](https://jitpack.io/#ergouser/neoscada-iec60870), with the tag as the version:

```groovy
repositories { maven { url = 'https://jitpack.io' } }

dependencies {
    implementation 'com.github.ergouser.neoscada-iec60870:org.eclipse.neoscada.protocol.iec60870.client.data:14.2.0'
    implementation 'com.github.ergouser.neoscada-iec60870:org.eclipse.neoscada.protocol.iec60870.server.data:14.2.0'
}
```

The classes run on Java 21 and later. `./gradlew publishToMavenLocal` installs them locally instead, as
`org.syncrontech.neoscada.protocols:<module>:14.2.0`.

| Module | What it is |
|---|---|
| `org.eclipse.neoscada.protocol.iec60870` | APCI and ASDU codecs, protocol options, TLS |
| `org.eclipse.neoscada.protocol.iec60870.client` | `Client` and `AutoConnectClient` (the controlling station) |
| `org.eclipse.neoscada.protocol.iec60870.client.data` | a client module delivering values and starting interrogation |
| `org.eclipse.neoscada.protocol.iec60870.server` | `Server` (the controlled station) |
| `org.eclipse.neoscada.protocol.iec60870.server.data` | a server module serving a `DataModel`: interrogation, spontaneous and background data, commands |
| `org.syncrontech.neoscada.protocol.iec60870.server.system` | clock synchronisation and test commands for the server |
| `org.eclipse.scada.utils` | NeoSCADA's utilities the above need |
| `...client.data.testing`, `...server.testing` | small example programs |

### Counters (integrated totals)

Energy and other counters travel as integrated totals: M_IT_NA_1 (single entries or a sequence) and M_IT_TB_1
(with a CP56Time2a time tag), each a `BinaryCounterReading` of a signed 32-bit count, its sequence number and its
carry and adjusted flags; the invalid flag is the reading's `QualityInformation`. A client reads them with a
counter interrogation:

```java
client.writeCommand(new CounterInterrogationCommand(header,
        CounterInterrogationCommand.qualifier(CounterInterrogationCommand.REQUEST_GENERAL,
                CounterInterrogationCommand.FREEZE_READ)));
```

and they arrive through `DataListener.update` like any other value. A server's `DataModel` answers by
implementing `readCounters`, which is given the cause (37 for all counters, 38 to 41 for a group) and the freeze
part of the qualifier, and pushes readings through `DataListener.dataChangeIntegratedTotals`; the module confirms,
sends what it pushes and terminates, as for a station interrogation. A model that does not implement it refuses
counter interrogation with cause 46. Spontaneous readings go out the same way with cause 3.

### TLS (IEC 62351-3)

```java
TlsOptions tls = TlsOptions.builder()
        .identity(Path.of("station.crt"), Path.of("station.key"), null)   // PEM; a KeyStore works too
        .trust(Path.of("utility-ca.crt"))
        .build();

Server server = new Server(new InetSocketAddress(TlsOptions.DEFAULT_PORT), options, modules, tls);
AutoConnectClient client = new AutoConnectClient(host, TlsOptions.DEFAULT_PORT, options, modules, listener, tls);
```

The defaults are the IEC 62351-3 profile: TLS 1.3 and 1.2 only (nothing lower can be enabled), a certificate
required of the client, ephemeral key exchange with AEAD suites only, the session keys renewed every hour (a key
update in 1.3, a renegotiation in 1.2), sessions resumable for an hour, and port 19998. Each can be changed for a
station that needs it. Revocation checking (OCSP, then the CRL) and a check of the server's name against its
certificate are there to turn on. Keys load from PKCS#8, PKCS#1 or SEC1 PEM without any library beyond the JDK.

Not done: IEC 62351-5 (authentication at the application layer, IEC 60870-5-7) and the security event logging of
IEC 62351-3 beyond what is logged through SLF4J.

What this fork changed
----------------------

Since Syncron Tech's 14.0.0:

- **Commands**: the select/execute bit was never sent, so a select arrived as an execute; the qualifier masks took
  that bit as well; and the positive/negative bit of the cause of transmission was never sent or read, so a
  negative confirmation looked positive. All were nine-bit masks on one-octet fields.
- **Link layer**: t1 now supervises sent I frames and STARTDT/STOPDT, not only TESTFR; the first acknowledgement
  went out after w-1 frames instead of w; a frame too short for its control fields is refused.
- **TLS** as above, through new constructors of `Client`, `AutoConnectClient` and `Server`; the old ones are
  unchanged.
- **Integrated totals and counter interrogation** (M_IT_NA_1, M_IT_TB_1, C_CI_NA_1) in both roles, as above.
- **Interrogation**: groups 10 to 16 were not recognised as interrogation causes, so the termination went out
  before data still waiting for the send window; an interrogation or counter interrogation with a cause other than
  activation was started as if activated, and now a deactivation is confirmed negatively (nothing is stopped) and
  any other cause is refused with cause 45.
- A read command (C_RD) is answered for every value type, not only single points and floats.
- `DataProcessor` can interrogate named common addresses instead of the broadcast address, which not every station
  answers.
- `Server` binds in its constructor, so a port in use fails there, and reports the port it bound.
- A client's disconnect carries its reason, a TLS refusal included.
- Gradle 9 wrapper, JitPack, Guava 33, SLF4J 2, Netty 4.1.130.

Testing
-------

`./gradlew build` runs the unit and loopback suites (no network beyond loopback):

- every registered ASDU type decoded from bytes laid out by hand from the standard, and encoded back;
- the link layer's framing, k and w windows and t1/t2/t3 timers, on a clock the test moves;
- a client and a server of this stack exchanging interrogation, spontaneous data, set points and select/execute,
  counter interrogation with reads, freezes and resets, group requests and refusals,
  in plain TCP and over TLS 1.2 and 1.3, with refusals of missing or untrusted certificates, wrong server names and
  weak suites, and key renewal under traffic.

`./gradlew interop` (needs Docker) runs it against an independent implementation, the core of
[IEC60870-5-104-Simulator](https://github.com/Karl-Dai/IEC60870-5-104-Simulator) (MIT), pinned to one commit and
built from `interop/`: the simulator as slave read and commanded by this client, and as master reading and
commanding this server, counters included, in plain TCP and over TLS 1.2 and 1.3.

Not yet supported
-----------------

Step positions (M_ST, C_RC), protection events (M_EP),
bitstring commands (C_BO), bitstrings as a sequence (M_BO_NA_1 with SQ=1), parameter loading (P_ME), file transfer, and normalized values without quality (M_ME_ND).
A received ASDU of a type not registered is answered with cause 44, unknown type.

History
-------

This source was extracted (with git filter-repo) from the Eclipse NeoSCADA repository by Syncron Tech, whose
notes follow.

> NeoSCADA itself uses Maven to build, but the whole thing is kind of complex beast with dozens of modules and
> dependencies we don't need.
>
> When writing this, the necessary modules are available in maven repositories, but they are old versions (0.4.x)
> and we need fixes done for 0.5 (which has never been released, there are only nightly builds in eclipse project
> servers). The NeoSCADA project itself seems dead now.
>
> `org.syncrontech.neoscada.protocol.iec60870.server.system` contains implementations for some messages that were
> missing from the Eclipse library.

Building
--------

`./gradlew build` with JDK 21 or later.

Related documentation
---------------------

- Beckhoff has some documents for [IEC-104 data structures][1].
- [Description and Analysis of IEC 104 Protocol][4], technical report by Petr Matoušek.
- Book "Practical Modern SCADA Protocols", chapters 7-9.
- [QTester IEC-104 client/master/scada][2]
- [FreyrSCADA tester][3]

[1]: https://infosys.beckhoff.com/english.php?content=../content/1033/tcplclibiec870_5_104/html/tcplclibiec870_5_104_telegrammstructure.htm&id
[2]: https://github.com/riclolsen/qtester104
[3]: https://github.com/FreyrSCADA/IEC-60870-5-104
[4]: https://www.fit.vut.cz/research/publication/11570/