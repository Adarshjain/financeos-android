package com.financeos.smssync

object TestConfig {
    const val JSON = """
{
  "catalogue": {
    "senders": [
      {"header": "TESTBK", "provider": "Test Bank", "bank": "t", "kind": "bank", "numberless": false, "families": []},
      {"header": "WALLET", "provider": "Wallet", "bank": null, "kind": "wallet", "numberless": true,
       "families": [{"key": "apay", "pattern": "Apay"}, {"key": "gift", "pattern": "gift card"}]},
      {"header": "PLAINW", "provider": "Plain wallet", "bank": null, "kind": "wallet", "numberless": true, "families": []}
    ],
    "drop": {
      "otpPatterns": ["(?i)\\botp\\b", "(?i)do\\s+not\\s+share"],
      "promoHeaderSuffixes": ["P"]
    },
    "endingPatterns": [
      {"key": "masked_group", "pattern": "(?i)(?:x+|\\*+)[\\s-]*(\\d{3,4})(?!\\d)"},
      {"key": "grouped_mask", "pattern": "\\d{3}-\\d{3}\\*+-(\\d{3})(?!\\d)"}
    ]
  },
  "lines": [
    {"id": "L1", "sender": "TESTBK", "ending": "1234", "family": null, "syncFrom": "2026-03-10"},
    {"id": "L2", "sender": "WALLET", "ending": null, "family": "apay", "syncFrom": null}
  ],
  "paused": false
}
"""
    val config: DeviceConfig get() = ConfigParser.parse(JSON)
    val classifier: LineClassifier get() = LineClassifier(config)
}
