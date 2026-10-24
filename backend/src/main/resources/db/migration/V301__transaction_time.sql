-- Local time of day a payment was made, when the bank export has it (card payments usually do).
-- Only reports use it; the budget math works on dates.
alter table txn add column time time;
