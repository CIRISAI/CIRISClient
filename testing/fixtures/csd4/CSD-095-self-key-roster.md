# CSD-095 — FIXTURE (not a card): the CSD/4 block CSD-095 converts to

**CSD**: CSD-095 · a fixture for `packaging/check_csd4.py --self-test` and
`testing/test_csd4.py`. The real card is `FSD/CSD/CSD-095-self-key-roster.md`
(CIRISClient#218). The block below is that card's §5 yaml, copied verbatim, with
the fence retagged `csd:tests` and one line added: `catalogue_version: 3`. It
mirrors CIRISServer#802 @ `003871fd`
`harness/native/topologies/catalog/requirements.yaml` `CSD-095`.

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

```yaml csd:tests
CSD-095:
  catalogue_version: 3
  topology: T10
  tests:
    add_held:
      at: [key.membership.proposed_add, device.join_requested]
      probe: {node: A2, path: /v1/self/roster/proposals, status: 200,
              expect: {proposals.0.proposal.kind: add, proposals.0.decision.outcome: pending}}
    evict_held:
      at: [key.membership.proposed_evict, key.holder.thief, self.roster_size.three_more]
      probe: {node: A2, path: /v1/self/roster/proposals, status: 200,
              expect: {proposals.0.proposal.kind: evict, proposals.0.decision.outcome: pending}}
    evicted_by_quorum:
      at: [key.membership.evicted, key.holder.thief]
      probe: {node: A2, path: /v1/self/roster/proposals, status: 200, expect: {proposals.0: null}}
```
