Throwaway fixture for the mc-okhtp live reproduction (mcd-jenkins-shared PR #126).

Three commits, in order:
  Z  this file only. The run that SUCCEEDS and becomes the anchor.
  A  adds GameModes/Custom/custom.tscn, which categorize() routes to 'client'.
     The run on this commit is ABORTED, so it must never become an anchor.
  B  adds docs/plans/, which categorize() routes to 'docs'.
     The run on this commit must still detect client=true, by anchoring on Z.

Delete this branch once the three probe runs are done.
