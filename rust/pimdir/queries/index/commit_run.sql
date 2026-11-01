-- Commits a run's transaction (begin_run). A run that fails is rolled back
-- by closing the handle, which ends its transaction unwritten.
RELEASE index_run;
