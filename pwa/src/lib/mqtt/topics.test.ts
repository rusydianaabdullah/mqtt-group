import { describe, expect, it } from 'vitest';
import {
	attachmentTopic,
	audioTopic,
	callTopic,
	controlTopic,
	everythingTopic,
	groupIdFor,
	isValidPrefix,
	kindOf,
	locationTopic,
	messagesTopic,
	normalisePrefix,
	presenceTopic,
	settingsTopic
} from './topics';

describe('topics', () => {
	it('builds the layout both implementations agree on', () => {
		expect(messagesTopic('familygroup', 'g1')).toBe('familygroup/g1/msg');
		expect(settingsTopic('familygroup', 'g1')).toBe('familygroup/g1/set');
		expect(locationTopic('familygroup', 'g1', 'u1')).toBe('familygroup/g1/loc/u1');
		expect(everythingTopic('familygroup', 'g1')).toBe('familygroup/g1/#');
	});

	it('trims a prefix to its useful middle', () => {
		expect(normalisePrefix('  /family/  ')).toBe('family');
		expect(normalisePrefix('')).toBe('familygroup');
		expect(normalisePrefix('///')).toBe('familygroup');
	});

	it('rejects a prefix that would not survive being pasted into a filter', () => {
		expect(isValidPrefix('family')).toBe(true);
		expect(isValidPrefix('a/b')).toBe(true);
		expect(isValidPrefix('a#b')).toBe(false);
		expect(isValidPrefix('a+b')).toBe(false);
		expect(isValidPrefix('a b')).toBe(false);
		expect(isValidPrefix('a//b')).toBe(false);
		expect(isValidPrefix('  ')).toBe(false);
	});

	it('reads the group and the kind back out of a topic', () => {
		expect(groupIdFor('familygroup/g1/loc/u1')).toBe('g1');
		expect(kindOf('familygroup/g1/loc/u1')).toBe('loc');
		expect(kindOf('familygroup/g1/att/b1')).toBe('att');
		// A prefix that happens to contain a level named like a kind must not be mistaken for
		// one: the first match wins, and it is the level after the group id.
		expect(groupIdFor('familygroup/g1/msg')).toBe('g1');
		expect(kindOf('familygroup/g1/unknown')).toBeNull();
	});
});

/**
 * The test that would have caught the phase 8 ringing bug on the day it was written.
 *
 * Every topic this app publishes has to be recognised by the receive side. On the Android side
 * those were two independent lists for a while — `Topics` built the topics and `GroupSync` kept
 * its own set of kinds — and adding `call` to one and not the other dropped every call message
 * between two phones at the first check: nothing logged, nothing thrown, no ring.
 *
 * This walks every builder rather than restating the list, so a new topic added without its kind
 * fails here rather than in somebody's living room.
 */
describe('every topic this app publishes', () => {
	const prefix = 'house/family';
	const group = 'g-1';
	const user = 'u-1';

	const all = () => [
		messagesTopic(prefix, group),
		controlTopic(prefix, group),
		settingsTopic(prefix, group),
		callTopic(prefix, group),
		audioTopic(prefix, group, user),
		attachmentTopic(prefix, group, 'blob-1'),
		locationTopic(prefix, group, user),
		presenceTopic(prefix, group, user)
	];

	it('is recognised by the receive side', () => {
		for (const topic of all()) {
			expect(kindOf(topic), `no kind for ${topic}`).not.toBeNull();
			// A prefix with a slash in it is the case that makes counting from the front wrong,
			// so the group id is found relative to the kind — and both have to work together.
			expect(groupIdFor(topic), `wrong group for ${topic}`).toBe(group);
		}
	});

	it('leaves a topic from a newer build alone rather than guessing', () => {
		expect(kindOf('familygroup/g-1/somethingnew')).toBeNull();
		expect(groupIdFor('familygroup/g-1/somethingnew')).toBeNull();
	});
});
