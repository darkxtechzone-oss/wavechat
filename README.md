# Wave Chat

Messenger yenye E2EE, bila seva ya kati. App yenyewe ndiyo inayoendesha kila kitu na MongoDB inatumika kama "posta" ya muda tu.
*This app provided by DarkX TECH*

## Jinsi inavyofanya kazi
1. Mtumaji anafunga ujumbe (ECDH P-256 + AES-256-GCM) kwa public key ya mpokeaji, kisha anauweka kwenye `wave_inbox` (`to` = mpokeaji).
2. App ya mpokeaji inakagua `wave_inbox`, inafungua ujumbe, **inauhifadhi kwenye simu (SQLite)**, kisha **inaufuta kwenye DB**.
3. Mpokeaji anaweka risiti kwenye `wave_receipts`; app ya mtumaji inaisoma na kuonyesha ✓✓.
4. Ujumbe ambao haujachukuliwa unafutwa na MongoDB yenyewe baada ya **saa 24** (TTL index kwenye `createdAt`).

## Collections (database `darkxyt`)
- `wave_users`: `_id`=username, name, salt, hash, pub, phoneHash?, created, seen
- `wave_inbox`: `_id`=messageId, from, to, ct, createdAt (TTL 24h)
- `wave_receipts`: `_id`=messageId, to, createdAt (TTL 24h)

Indexes (pamoja na TTL) zinatengenezwa na app yenyewe mara ya kwanza inapounganisha.

## Kuweka
1. Weka MongoDB connection string kwenye `app/src/main/assets/mongo.txt`.
2. Push kwenye GitHub; workflow ya `Build APK` itatengeneza `WaveChat.apk` (Artifacts za run).
3. Makosa ya compile yanaonekana kwenye Annotations za run.

## Mipaka (kwa uwazi)
- Mongo URI iko ndani ya APK: yeyote anaweza kuisoma na kufuta data (maudhui ya ujumbe hayasomeki kwa sababu ya E2EE).
- Hakuna push ya kweli bila seva: ujumbe unapokelewa kwa foreground service (arifa ya kudumu) au app ikiwa wazi.
- Simu ikipotea, historia inapotea; akaunti moja kwa simu moja (ufunguo uko kwenye simu).
