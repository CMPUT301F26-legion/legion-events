// Rules checks for editing a lottery after it is created (US 02.04.06).
import { readFileSync } from 'node:fs';
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, updateDoc, serverTimestamp, Timestamp, GeoPoint, setLogLevel } from 'firebase/firestore';

const endpoint = process.env.FIRESTORE_EMULATOR_HOST;
assert.ok(endpoint, 'Set FIRESTORE_EMULATOR_HOST to run the rules tests.');
const [host, port] = endpoint.split(':');
const rules = readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8');
let environment;
setLogLevel('silent');

const minute = 60_000;
const day = 86_400_000;
const at = offset => Timestamp.fromMillis(Date.now() + offset);

/** A lottery owned by `owner` on campsite `camp`; registration started `startOffset` ms from now. */
function lottery({ startOffset = day, endOffset = 10 * day, entrantCount = 0, winnersDrawn = false } = {}) {
  return { ownerId: 'owner', campsiteIds: ['camp'],
    registrationStartAt: at(startOffset), registrationEndAt: at(endOffset),
    bookingStartAt: at(20 * day), bookingEndAt: at(30 * day), winnerCount: 2, waitlistLimit: 50,
    entrantCount, status: 'open', winnersDrawn, createdAt: serverTimestamp(), updatedAt: serverTimestamp() };
}

async function seed(path, data) {
  await environment.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), path), data));
}

const owner = () => environment.authenticatedContext('owner').firestore();
const edit = (id, fields) => updateDoc(doc(owner(), `lotteries/${id}`), { ...fields, updatedAt: serverTimestamp() });

before(async () => {
  environment = await initializeTestEnvironment({
    projectId: `demo-lottery-edit-${process.pid}`,
    firestore: { host, port: Number(port), rules },
  });
  await seed('users/owner', { name: 'Owner', email: 'o@example.com', phone: '', role: 'user', photoId: null,
    notificationsEnabled: true, fcmToken: null, disabled: false, createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
  for (const id of ['camp', 'camp2']) {
    await seed(`campsites/${id}`, { ownerId: 'owner', name: id, description: '', locationName: 'Here',
      location: new GeoPoint(53, -113), maxGuests: 4, pricePerNight: 1000, amenities: [], tags: [], rules: '',
      cancellationPolicy: '', availableNights: [], photoId: null, ratingAverage: 0, ratingCount: 0, active: true,
      createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
  }
});

after(async () => { if (environment) await environment.cleanup(); });

test('before registration opens, every editable field can change', async () => {
  await seed('lotteries/future', lottery());
  await assertSucceeds(edit('future', { winnerCount: 3, campsiteIds: ['camp', 'camp2'], registrationStartAt: at(2 * day) }));
  await assertFails(edit('future', { registrationStartAt: at(-minute) }));
});

test('after registration opens, the lottery is editable but its first day is fixed', async () => {
  await seed('lotteries/open', lottery({ startOffset: -day }));
  await assertSucceeds(edit('open', { winnerCount: 4, registrationEndAt: at(12 * day) }));
  await assertFails(edit('open', { registrationStartAt: at(day) }));
  await assertFails(edit('open', { registrationEndAt: at(-minute) }));
});

test('with entrants, campsites are fixed and the limit cannot drop below them', async () => {
  await seed('lotteries/joined', lottery({ startOffset: -day, entrantCount: 5 }));
  await assertSucceeds(edit('joined', { waitlistLimit: 5, winnerCount: 3 }));
  await assertFails(edit('joined', { waitlistLimit: 4, winnerCount: 3 }));
  await assertFails(edit('joined', { campsiteIds: ['camp', 'camp2'] }));
});

test('after registration closes or the draw, nothing can be edited', async () => {
  await seed('lotteries/closed', lottery({ startOffset: -10 * day, endOffset: -minute }));
  await assertFails(edit('closed', { winnerCount: 3 }));
  await seed('lotteries/drawn', lottery({ startOffset: -day, winnersDrawn: true }));
  await assertFails(edit('drawn', { winnerCount: 3 }));
});

test('only the owner can edit', async () => {
  await seed('lotteries/other', lottery({ startOffset: -day }));
  await assertFails(updateDoc(doc(environment.authenticatedContext('other').firestore(), 'lotteries/other'),
    { winnerCount: 3, updatedAt: serverTimestamp() }));
});
