// Copyright (c) 2026 ErgoTech Systems, Inc. Eclipse Public License v1.0.
//
// The interoperability oracle: the IEC60870-5-104-Simulator core (MIT) as an independent implementation for this
// stack to talk to. It writes one line per fact to stdout, which the Java tests read:
//
//   READY                       the slave is listening
//   POINT <ioa> <type> <value>  a point the master holds after interrogation
//   SENT <what>                 the master sent a command
//   DONE                        the master is finished
//
// Usage:
//   iec104-oracle slave  <port>        [--tls <cert> <key> <ca>] [--tls12]
//   iec104-oracle master <host> <port> [--tls <cert> <key> <ca>] [--tls12]

use iec104sim_core::data_point::{DataPoint, DataPointValue, InformationObjectDef};
use iec104sim_core::master::{MasterConfig, MasterConnection, TlsConfig, TlsVersionPolicy};
use iec104sim_core::slave::{RemoteOperationConfig, SlaveServer, SlaveTlsConfig, SlaveTransportConfig, Station};
use iec104sim_core::types::AsduTypeId;
use std::io::Write;
use tokio::time::{sleep, Duration};

struct Tls {
    cert: String,
    key: String,
    ca: String,
    tls12: bool,
}

fn tls_option(args: &[String]) -> Option<Tls> {
    let tls12 = args.iter().any(|a| a == "--tls12");
    args.iter().position(|a| a == "--tls").map(|i| Tls {
        cert: args[i + 1].clone(),
        key: args[i + 2].clone(),
        ca: args[i + 3].clone(),
        tls12,
    })
}

fn say(line: String) {
    let mut out = std::io::stdout().lock();
    writeln!(out, "{}", line).unwrap();
    out.flush().unwrap();
}

fn value_text(value: &DataPointValue) -> String {
    match value {
        DataPointValue::SinglePoint { value } => value.to_string(),
        DataPointValue::DoublePoint { value } => value.to_string(),
        DataPointValue::StepPosition { value, .. } => value.to_string(),
        DataPointValue::Bitstring { value } => format!("0x{:08X}", value),
        DataPointValue::Normalized { value } => value.to_string(),
        DataPointValue::Scaled { value } => value.to_string(),
        DataPointValue::ShortFloat { value } => value.to_string(),
        DataPointValue::IntegratedTotal { value, carry, sequence } => format!("{} sq={} cy={}", value, sequence, carry),
    }
}

/// The station the slave serves, common address 1: one point of each monitored type the Java stack decodes.
fn station() -> Station {
    let mut station = Station::new(1, "Interop");
    let points = [
        (1, AsduTypeId::MSpNa1, DataPointValue::SinglePoint { value: true }),
        (2, AsduTypeId::MDpNa1, DataPointValue::DoublePoint { value: 2 }),
        (3, AsduTypeId::MMeNa1, DataPointValue::Normalized { value: 0.5 }),
        (4, AsduTypeId::MMeNb1, DataPointValue::Scaled { value: -1234 }),
        (5, AsduTypeId::MMeNc1, DataPointValue::ShortFloat { value: 1.5 }),
        (6, AsduTypeId::MBoNa1, DataPointValue::Bitstring { value: 0x1234_5678 }),
        (7, AsduTypeId::MSpTb1, DataPointValue::SinglePoint { value: false }),
        (8, AsduTypeId::MMeTf1, DataPointValue::ShortFloat { value: -40.25 }),
        // counters, sent only for a counter interrogation
        (9, AsduTypeId::MItNa1, DataPointValue::IntegratedTotal { value: 123_456, carry: true, sequence: 3 }),
        (10, AsduTypeId::MItNa1, DataPointValue::IntegratedTotal { value: -7, carry: false, sequence: 0 }),
    ];
    for (ioa, asdu_type, value) in points {
        station
            .add_point(InformationObjectDef {
                ioa,
                asdu_type,
                category: asdu_type.category(),
                name: String::new(),
                comment: String::new(),
                mapping: None,
                command_qualifier: None,
                select_before_operate: None,
            })
            .unwrap();
        station.data_points.insert(DataPoint::with_value(ioa, asdu_type, value));
    }
    station
}

async fn slave(args: &[String]) {
    let port: u16 = args[2].parse().unwrap();
    let mut transport = SlaveTransportConfig { bind_address: "0.0.0.0".to_string(), port, ..Default::default() };
    if let Some(tls) = tls_option(args) {
        transport.tls = SlaveTlsConfig {
            enabled: true,
            cert_file: tls.cert,
            key_file: tls.key,
            ca_file: tls.ca,
            require_client_cert: true,
            ..Default::default()
        };
    }
    let mut server = SlaveServer::new(transport);
    server
        .set_remote_ops(RemoteOperationConfig { auto_map_commands: true, ..Default::default() })
        .await;
    server.add_station(station()).await.unwrap();
    server.start().await.unwrap();
    say("READY".to_string());
    loop {
        sleep(Duration::from_secs(3600)).await;
    }
}

async fn master(args: &[String]) {
    let mut config = MasterConfig {
        target_address: args[2].clone(),
        port: args[3].parse().unwrap(),
        common_address: 1,
        ..Default::default()
    };
    if let Some(tls) = tls_option(args) {
        config.tls = TlsConfig {
            enabled: true,
            ca_file: tls.ca,
            cert_file: tls.cert,
            key_file: tls.key,
            version: if tls.tls12 { TlsVersionPolicy::Tls12Only } else { TlsVersionPolicy::Auto },
            ..Default::default()
        };
    }
    let mut master = MasterConnection::new(config);
    master.connect().await.unwrap();
    sleep(Duration::from_millis(500)).await;
    master.send_interrogation(1).await.unwrap();
    sleep(Duration::from_millis(2000)).await;
    // counter interrogation, all counters, read (QCC 5)
    master.send_counter_read(1).await.unwrap();
    sleep(Duration::from_millis(1000)).await;
    {
        let data = master.received_data.read().await;
        if let Some(map) = data.ca_map(1) {
            for point in map.all_sorted() {
                say(format!("POINT {} {} {}", point.ioa, point.asdu_type.name(), value_text(&point.value)));
            }
        }
    }
    // set point, short float, direct execute
    master.send_setpoint_float(102, 7.25, false, 1, 0, 6).await.unwrap();
    say("SENT C_SE_NC_1 102 7.25 execute".to_string());
    sleep(Duration::from_millis(500)).await;
    // single command: select, then execute
    master.send_single_command(200, false, true, 1, 0, 6).await.unwrap();
    say("SENT C_SC_NA_1 200 false select".to_string());
    sleep(Duration::from_millis(500)).await;
    master.send_single_command(200, false, false, 1, 0, 6).await.unwrap();
    say("SENT C_SC_NA_1 200 false execute".to_string());
    sleep(Duration::from_millis(1000)).await;
    master.disconnect().await.unwrap();
    say("DONE".to_string());
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("slave") => slave(&args).await,
        Some("master") => master(&args).await,
        _ => {
            eprintln!("usage: iec104-oracle slave <port> | master <host> <port> [--tls <cert> <key> <ca>] [--tls12]");
            std::process::exit(2);
        }
    }
}
