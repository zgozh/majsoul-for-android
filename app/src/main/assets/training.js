(() => {
    'use strict';
    if (self !== top || !self.ZheyouTraining) return;

    const bridge = self.ZheyouTraining;
    const send = (type, extra = {}) => bridge.postMessage(JSON.stringify({type, ...extra}));
    const readText = new TextDecoder('utf-8', {fatal: true});
    const xorKey = [0x84, 0x5e, 0x4e, 0x42, 0x39, 0xa2, 0x1f, 0x60, 0x1c];
    const auth = '.lq.FastTest.authGame';
    const restoreMethods = new Set(['.lq.FastTest.enterGame', '.lq.FastTest.syncGame']);
    const inputMethods = new Set(['.lq.FastTest.inputOperation', '.lq.FastTest.inputChiPengGang']);
    const endMethods = new Set(['.lq.NotifyGameEndResult', '.lq.NotifyGameTerminate']);
    const endRound = new Set(['ActionHule', 'ActionNoTile', 'ActionLiuJu']);
    const empty = new Uint8Array();
    let active;

    // Read only the Liqi fields needed for MJAI conversion.
    function reader(bytes) {
        let offset = 0;
        return {
            more: () => offset < bytes.length,
            uint() {
                let value = 0;
                for (let shift = 0; shift < 70 && offset < bytes.length; shift += 7) {
                    const byte = bytes[offset++];
                    if (shift < 32) value |= (byte & 127) << shift;
                    if (byte < 128) return value >>> 0;
                }
                throw new Error('Invalid varint');
            },
            take(size) {
                if (size > bytes.length - offset) throw new Error('Truncated field');
                const result = bytes.subarray(offset, offset + size);
                offset += size;
                return result;
            },
        };
    }

    function fields(bytes = empty) {
        const input = reader(bytes);
        const values = new Map();
        while (input.more()) {
            const tag = input.uint();
            const id = tag >>> 3;
            let value;
            switch (tag & 7) {
                case 0: value = input.uint(); break;
                case 1: input.take(8); break;
                case 2: value = input.take(input.uint()); break;
                case 5: input.take(4); break;
                default: throw new Error('Unsupported wire type');
            }
            if (value === undefined) continue;
            if (!values.has(id)) values.set(id, []);
            values.get(id).push(value);
        }
        const all = id => values.get(id) || [];
        const blobs = id => all(id).filter(value => value instanceof Uint8Array);
        return {
            blobs,
            read: id => fields(blobs(id).at(-1)),
            num: id => all(id).filter(Number.isInteger).at(-1) ?? 0,
            texts: id => blobs(id).map(value => readText.decode(value)),
            text: id => readText.decode(blobs(id).at(-1) ?? empty),
            nums: id => all(id).flatMap(value => {
                if (Number.isInteger(value)) return [value];
                const packed = reader(value);
                const result = [];
                while (packed.more()) result.push(packed.uint());
                return result;
            }),
        };
    }

    function tile(raw) {
        if (/^[1-9][mps]$/.test(raw)) return raw;
        if (/^0[mps]$/.test(raw)) return '5' + raw[1] + 'r';
        if (/^[1-7]z$/.test(raw)) return 'ESWNPFC'[Number(raw[0]) - 1];
        throw new Error('Unexpected tile');
    }

    function playerTable(account, reply) {
        const seats = reply.nums(3);
        const seat = seats.indexOf(account);
        if (!account || reply.read(1).num(1) || ![3, 4].includes(seats.length) ||
            seat < 0 || seat !== seats.lastIndexOf(account)) return null;
        return {seat, count: seats.length};
    }

    function observe(socket) {
        const pending = new Map();
        let table;
        let round;
        let lastStep;
        let queue = Promise.resolve();

        function resetRound() { round = undefined; lastStep = undefined; }

        function decision(operation, elapsed = 0) {
            if (!round || !operation || operation.num(1) !== table.seat || !operation.blobs(2).length) {
                send('watching');
                return;
            }
            const windowMs = operation.num(4) + operation.num(5);
            if (windowMs > 0 && elapsed >= windowMs) { send('watching'); return; }
            send('decision', {
                remainingMs: windowMs > 0 ? windowMs - elapsed : 0,
                request: {player_id: table.seat,
                    model: table.count === 3 ? 'epsilon-prism-3p' : 'mortal-s42', events: round.events},
            });
        }

        function newRound(data) {
            const {seat, count} = table;
            const oya = data.num(2);
            const wind = 'ESWN'[data.num(1)];
            const scores = data.nums(6).map(value => value | 0);
            const tiles = data.texts(4).map(tile);
            if (!wind || oya >= count || scores.length !== count || tiles.length !== (oya === seat ? 14 : 13)) {
                throw new Error('Unexpected opening');
            }
            const doras = data.texts(14).map(tile);
            if (!doras.length) doras.push(tile(data.text(5)));
            const hands = Array.from({length: count}, () => Array(13).fill('?'));
            hands[seat] = tiles.slice(0, 13);
            round = {doras: [doras[0]], delayed: [], delayKan: false, reach: undefined,
                pons: new Map(), events: [
                    {type: 'start_game', id: seat, num_players: count, names: Array(count).fill('')},
                    {type: 'start_kyoku', bakaze: wind, kyoku: oya + 1, honba: data.num(3),
                        kyotaku: data.num(8), oya, scores, tehais: hands, dora_marker: doras[0]},
                    {type: 'tsumo', actor: oya, pai: oya === seat ? tiles[13] : '?'},
                ]};
            return data.read(7);
        }

        function addedDoras(data, field) {
            const counts = data.texts(field).map(tile);
            if (counts.some((pai, i) => i < round.doras.length && pai !== round.doras[i])) {
                throw new Error('Dora indicators changed');
            }
            const added = counts.slice(round.doras.length);
            round.doras.push(...added);
            return added;
        }

        function reveal(doras) {
            for (const dora_marker of doras) round.events.push({type: 'dora', dora_marker});
        }

        function play(name, data) {
            const actor = data.num(1);
            if (actor >= table.count) throw new Error('Invalid actor');
            const events = round.events;
            if (round.reach !== undefined) {
                const liqi = ['ActionDealTile', 'ActionChiPengGang'].includes(name) ? data.read(5) : null;
                if (!liqi || !liqi.num(4)) events.push({type: 'reach_accepted', actor: round.reach});
                round.reach = undefined;
            }
            switch (name) {
                case 'ActionDealTile': {
                    const markers = addedDoras(data, 6);
                    if (round.delayKan) round.delayed.push(...markers);
                    else reveal([...round.delayed.splice(0), ...markers]);
                    events.push({type: 'tsumo', actor, pai: actor === table.seat ? tile(data.text(2)) : '?'});
                    return data.read(4);
                }
                case 'ActionDiscardTile': {
                    reveal([...round.delayed.splice(0), ...addedDoras(data, 8)]);
                    round.delayKan = false;
                    if (data.num(3) || data.num(9)) {
                        events.push({type: 'reach', actor});
                        round.reach = actor;
                    }
                    events.push({type: 'dahai', actor, pai: tile(data.text(2)), tsumogiri: !!data.num(5)});
                    return data.read(4);
                }
                case 'ActionChiPengGang': {
                    const kind = data.num(2);
                    const type = ['chi', 'pon', 'daiminkan'][kind];
                    const tiles = data.texts(3).map(tile);
                    const from = data.nums(4);
                    const source = from.findIndex(seat => seat !== actor);
                    if (!type || (kind === 0 && table.count === 3) || source < 0 ||
                        tiles.length !== (kind === 2 ? 4 : 3) || from.length !== tiles.length ||
                        from.some(seat => seat >= table.count) || from.filter(seat => seat !== actor).length !== 1) {
                        throw new Error('Invalid meld');
                    }
                    const pai = tiles[source];
                    events.push({type, actor, target: from[source], pai, consumed: tiles.filter((_, i) => i !== source)});
                    if (kind === 1) round.pons.set(actor + pai.replace(/r$/, ''), tiles);
                    if (kind === 2) round.delayKan = true;
                    return data.read(6);
                }
                case 'ActionAnGangAddGang': {
                    const kind = data.num(2);
                    const pai = tile(data.text(3));
                    const normal = pai.replace(/r$/, '');
                    if (kind === 2) {
                        const consumed = round.pons.get(actor + normal);
                        if (!consumed) throw new Error('Added kan without pon');
                        events.push({type: 'kakan', actor, pai, consumed});
                        round.delayKan = true;
                        round.delayed.push(...addedDoras(data, 6));
                    } else if (kind === 3) {
                        const red = /^5[mps]$/.test(normal) && (table.count === 4 || normal !== '5m');
                        const consumed = [red ? normal + 'r' : normal, normal, normal, normal];
                        events.push({type: 'ankan', actor, consumed});
                        round.delayKan = false;
                        const markers = addedDoras(data, 6);
                        if (markers.length) reveal([...round.delayed.splice(0), ...markers]);
                    } else throw new Error('Invalid kan');
                    return data.read(4);
                }
                case 'ActionBaBei':
                    if (table.count !== 3) throw new Error('Unexpected nuki');
                    reveal([...round.delayed.splice(0), ...addedDoras(data, 6)]);
                    round.delayKan = false;
                    events.push({type: 'kita', actor, pai: 'N'});
                    return data.read(4);
                default: throw new Error('Unknown action in round');
            }
        }

        function applyAction(prototype, encoded) {
            const name = prototype.text(2);
            const step = prototype.num(1);
            if (!round && name !== 'ActionNewRound') return {ignored: true};
            if (lastStep !== undefined && step <= lastStep) return {ignored: true};
            if (round && name !== 'ActionNewRound' && step !== lastStep + 1) throw new Error('Missing action');
            if (endRound.has(name)) {
                // The server starts a fresh action sequence for the next hand on this socket.
                resetRound();
                return {};
            }
            lastStep = step;
            if (name === 'ActionMJStart') return {};
            const raw = prototype.blobs(3).at(-1) ?? empty;
            const data = fields(encoded ? raw.map((value, i) =>
                value ^ (((23 ^ raw.length) + 5 * i + xorKey[i % xorKey.length]) & 255)) : raw);
            if (name === 'ActionNewRound') return {operation: newRound(data)};
            return {operation: play(name, data)};
        }

        function frame(outbound, bytes) {
            if (!bytes || bytes.length < 2 || bytes.length > 1_048_576) return;
            const kind = bytes[0];
            const id = bytes[1] | (bytes[2] << 8);
            if (outbound && kind === 2) {
                const message = fields(bytes.subarray(3));
                const name = message.text(1);
                if (name === auth) {
                    active = socket;
                    table = undefined;
                    pending.clear();
                    resetRound();
                    send('idle');
                    pending.set(id, {name, account: message.read(2).num(1)});
                } else if (active === socket && table && restoreMethods.has(name)) {
                    pending.set(id, {name});
                    send('watching');
                } else if (active === socket && table && inputMethods.has(name)) send('watching');
                return;
            }
            if (outbound || active !== socket) return;
            if (kind === 3 && pending.has(id)) {
                const {name, account} = pending.get(id);
                pending.delete(id);
                const reply = fields(bytes.subarray(3)).read(2);
                if (name === auth) {
                    table = playerTable(account, reply);
                    send(table ? 'watching' : 'idle');
                } else if (table) {
                    resetRound();
                    const restore = reply.read(4);
                    let last = {};
                    if (!reply.read(1).num(1) && !reply.num(2)) {
                        for (const action of restore.blobs(2)) last = applyAction(fields(action), false);
                    }
                    decision(last.operation, restore.num(3));
                }
            } else if (kind === 1 && table) {
                const message = fields(bytes.subarray(1));
                const name = message.text(1);
                if (endMethods.has(name)) {
                    table = undefined;
                    resetRound();
                    send('idle');
                } else if (name === '.lq.ActionPrototype') {
                    const action = applyAction(message.read(2), true);
                    if (!action.ignored) decision(action.operation);
                }
            }
        }

        function enqueue(outbound, data) {
            queue = queue.then(async () => {
                if (data instanceof Blob && data.size > 1_048_576) return;
                const bytes = data instanceof Blob ? new Uint8Array(await data.arrayBuffer()) : data;
                frame(outbound, bytes);
            }).catch(() => {
                if (active === socket && table) { resetRound(); send('gap'); }
            });
        }

        socket.addEventListener('message', event => {
            if (event.data instanceof ArrayBuffer) enqueue(false, new Uint8Array(event.data));
            else if (event.data instanceof Blob) enqueue(false, event.data);
        });
        socket.addEventListener('close', () => {
            queue = queue.then(() => {
                if (active === socket) { active = undefined; send('idle'); }
            });
        });
        const originalSend = socket.send;
        socket.send = function (data) {
            const result = originalSend.call(this, data);
            if (data instanceof ArrayBuffer) enqueue(true, new Uint8Array(data).slice());
            else if (ArrayBuffer.isView(data)) enqueue(true, new Uint8Array(data.buffer, data.byteOffset, data.byteLength).slice());
            else if (data instanceof Blob) enqueue(true, data);
            return result;
        };
    }

    self.WebSocket = new Proxy(self.WebSocket, {
        construct(target, args, newTarget) {
            const socket = Reflect.construct(target, args, newTarget);
            observe(socket);
            return socket;
        },
    });
})();
