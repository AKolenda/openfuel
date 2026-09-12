# SPDX-License-Identifier: AGPL-3.0-only
import copy
import unittest
from policy import *

class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.s=Station('test','Sample Fuel',45.42,-75.7)
        self.secret=b'prototype-test-secret-32-bytes!!!'
    def new(self):return Proposal('p',Change.NEW,self.s,None)
    def reviewed(self,change,station=None):
        s=station or self.s;p=Proposal('p',change,s,s.version);p.review('reviewer-a',evidence_checked=True);return p
    def test_repeat_attester_only_counts_once(self):
        p=self.new();token=scoped_attester(self.secret,p.id,'a')
        for _ in range(10):p.attest(token)
        self.assertFalse(p.triage_ready)
    def test_three_attesters_prioritize_without_publishing(self):
        p=self.new()
        for a in ['a','b','c']:p.attest(scoped_attester(self.secret,p.id,a))
        self.assertTrue(p.triage_ready)
        with self.assertRaises(ValueError):p.publish(None)
    def test_new_station_needs_moderation(self):
        p=self.new();p.review('a',evidence_checked=True);s=p.publish(None)
        self.assertEqual(s.status,Status.ACTIVE);self.assertEqual(p.state,'accepted')
    def test_existing_station_conflict(self):
        p=self.new();p.review('a',evidence_checked=True)
        with self.assertRaises(ValueError):p.publish(self.s)
    def test_permanent_closure_two_reviewers(self):
        p=self.reviewed(Change.PERM_CLOSE)
        with self.assertRaises(ValueError):p.publish(self.s)
        p.review('reviewer-a',evidence_checked=True)
        with self.assertRaises(ValueError):p.publish(self.s)
        p.review('reviewer-b',evidence_checked=True)
        self.assertEqual(p.publish(self.s).status,Status.PERMANENT_CLOSED)
    def test_temporary_not_permanent(self):
        self.assertEqual(self.reviewed(Change.TEMP_CLOSE).publish(self.s).status,Status.TEMPORARY_CLOSED)
    def test_reopen_keeps_same_id(self):
        closed=replace(self.s,status=Status.PERMANENT_CLOSED,version=3)
        opened=self.reviewed(Change.REOPEN,closed).publish(closed)
        self.assertEqual(opened.id,closed.id);self.assertEqual(opened.version,4);self.assertEqual(opened.status,Status.ACTIVE)
    def test_edit_cannot_change_status(self):
        p=Proposal('p',Change.EDIT,replace(self.s,status=Status.PERMANENT_CLOSED),self.s.version)
        p.review('a',evidence_checked=True)
        self.assertEqual(p.publish(self.s).status,Status.ACTIVE)
    def test_stale_decision_rejected(self):
        p=self.reviewed(Change.EDIT)
        with self.assertRaises(ValueError):p.publish(replace(self.s,version=2))
    def test_no_double_publish(self):
        p=self.reviewed(Change.EDIT);p.publish(self.s)
        with self.assertRaises(ValueError):p.publish(self.s)
    def test_rejected_proposal_stays_rejected(self):
        p=self.new();p.reject()
        with self.assertRaises(ValueError):p.review('a',evidence_checked=True)
    def test_unchecked_evidence_rejected(self):
        with self.assertRaises(ValueError):self.new().review('a',evidence_checked=False)
    def test_tokens_not_linkable_between_proposals(self):
        self.assertNotEqual(scoped_attester(self.secret,'p','a'),scoped_attester(self.secret,'q','a'))
    def test_close_stations_only_flagged_for_review(self):
        opposite=replace(self.s,id='opposite',longitude=-75.7001)
        far=replace(self.s,id='far',latitude=46)
        matches=nearby_candidates(self.s,[opposite,far]);self.assertEqual([x.id for x in matches],['opposite'])
        self.assertEqual(opposite.status,Status.ACTIVE)
    def test_coordinate_validation(self):
        with self.assertRaises(ValueError):replace(self.s,latitude=float('nan'))
        with self.assertRaises(ValueError):replace(self.s,longitude=181)
    def event(self):
        p=self.new();p.review('a',evidence_checked=True);s=p.publish(None);ledger=[];append_public_event(ledger,p,s);return ledger
    def test_public_export_has_no_identities(self):
        payload=json.dumps(self.event());self.assertNotIn('_reviewers',payload);self.assertNotIn('_attesters',payload);self.assertNotIn('account',payload)
    def test_valid_ledger(self):self.assertTrue(verify_ledger(self.event()))
    def test_tampering_detected(self):
        ledger=self.event();ledger[0]['station']['name']='Changed';self.assertFalse(verify_ledger(ledger))
    def test_truncation_detected_against_retained_checkpoint(self):
        ledger=self.event();self.assertFalse(verify_ledger([], (1,ledger[0]['hash'])))
    def test_no_public_pending_event(self):
        with self.assertRaises(ValueError):append_public_event([],self.new(),self.s)

if __name__=='__main__':unittest.main(verbosity=2)
