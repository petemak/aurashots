(ns aurashots.features.dashboard.api
  "Slice `dashboard` (architecture.md section 2.3): composes the admin dashboard figures from the `api` namespaces of
  booking, orders and enquiries. It has no store of its own."
  (:require [aurashots.features.booking.api :as booking]
            [aurashots.features.enquiries.api :as enquiries]
            [aurashots.features.orders.api :as orders]))

(defn figures
  "Returns the dashboard body of api-contract.md section 3.2 for time `now` (an Instant). Read-only."
  [conn now]
  (merge (booking/dashboard-figures conn now)
         {:pendingOrders (orders/pending-count conn)
          :unansweredEnquiries (enquiries/unanswered-count conn)}))
