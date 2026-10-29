(ns aurashots.shared.money
  "Integer RwF arithmetic helpers (api-contract.md section 1: money is an integer, never decimal).
  Shared pure code; no I/O.")

(defn sum
  "Sums a collection of integer RwF amounts. Returns 0 for an empty collection.
  Throws if any element is nil (callers decide what a missing price means)."
  [amounts]
  (reduce + 0 amounts))
