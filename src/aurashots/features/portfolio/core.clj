(ns aurashots.features.portfolio.core
  "Slice `portfolio`, pure logic (architecture.md section 2.3): builds portfolio item records and their
  public and admin views. No I/O.")

(defn build-item
  "Builds the stored item from validated `body` `{:imageId :caption :description :categoryId :featured}`.
  `existing` is the current record or nil; `id`, `sort-order` and `now` are used for new items."
  [body {:keys [id sort-order now existing]}]
  (merge {:id id :sortOrder sort-order :createdAt now}
         existing
         (select-keys body [:imageId :caption :description :categoryId :featured])))

(defn public-view
  "Builds the public item `{id image caption description categoryId categoryName}`; `image` is an `Image` whose
  alt text is the item's description, `category-name` may be nil when the category was removed."
  [item image category-name]
  {:id (:id item) :image image :caption (:caption item) :description (:description item)
   :categoryId (:categoryId item) :categoryName category-name})

(defn admin-view
  "Builds the admin item `{id image caption description categoryId featured sortOrder}`."
  [item image]
  {:id (:id item) :image image :caption (:caption item) :description (:description item)
   :categoryId (:categoryId item) :featured (boolean (:featured item)) :sortOrder (:sortOrder item)})
