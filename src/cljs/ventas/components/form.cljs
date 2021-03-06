(ns ventas.components.form
  "Form stuff"
  (:require
   [cljs.reader :as reader]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [reagent.core :as reagent]
   [ventas.components.amount-input :as amount-input]
   [ventas.components.base :as base]
   [ventas.components.i18n-input :as i18n-input]
   [ventas.i18n :refer [i18n]]
   [ventas.utils.validation :as validation]))

(defn get-data [db db-path]
  (get-in db (conj db-path :form)))

(defn assoc-hash [db db-path]
  (assoc-in db
            (conj db-path :form-state :hash)
            (hash (get-data db db-path))))

(rf/reg-event-db
 ::set-field
 (fn [db [_ db-path field value & {:keys [reset-hash?]}]]
   {:pre [(vector? db-path)]}
   (let [form-field (if-not (sequential? field) [field] field)
         validators (get-in db (concat db-path [:form-state :validators]))]
     (as-> db %
           (assoc-in % (concat db-path [:form] form-field)
                     value)
           (assoc-in % (concat db-path [:form-state :validation] form-field)
                     (:infractions (validation/validate validators field value)))
           (if-not reset-hash?
             %
             (assoc-hash % db-path))))))

(rf/reg-event-fx
 ::update-field
 (fn [{:keys [db]} [_ db-path field update-fn]]
   {:pre [(vector? db-path)]}
   (let [field (if-not (sequential? field)
                 [field]
                 field)
         new-value (update-fn (get-in db (concat db-path [:form] field)))]
     {:dispatch [::set-field db-path field new-value]})))

(rf/reg-event-db
 ::populate
 (fn [db [_ {:keys [validators db-path] :as db-path-or-config} data]]
   {:pre [(or (vector? db-path-or-config) (map? db-path-or-config))]}
   (let [db-path (if (map? db-path-or-config)
                   db-path
                   db-path-or-config)]
     (-> db
         (assoc-in (conj db-path :form) data)
         (assoc-in (conj db-path :form-state) {:validators validators
                                               :validation {}})
         (assoc-hash db-path)))))

(rf/reg-sub
 ::data
 (fn [db [_ db-path]]
   (get-data db db-path)))

(defn get-state [db db-path]
  (get-in db (conj db-path :form-state)))

(rf/reg-sub
 ::state
 (fn [db [_ db-path]]
   (get-state db db-path)))

(rf/reg-sub
 ::infractions
 (fn [[_ db-path]]
   (rf/subscribe [::state db-path]))
 (fn [{:keys [validation]}]
   validation))

(rf/reg-sub
 ::field.infractions
 (fn [[_ db-path]]
   (rf/subscribe [::infractions db-path]))
 (fn [infractions [_ db-path key]]
   (get infractions key)))

(rf/reg-sub
 ::valid?
 (fn [[_ db-path]]
   (rf/subscribe [::state db-path]))
 (fn [{:keys [validation]}]
   (empty? (apply concat (vals validation)))))

(defn form [db-path content]
  (let [{:keys [hash]} @(rf/subscribe [::state db-path])]
    (with-meta content {:key hash})))

(def ^:private known-keys #{:value :type :db-path :key :label :width :inline-label :on-change-fx})

(defmulti input (fn [{:keys [type]}] type) :default :default)

(defn- checkbox [{:keys [value toggle db-path key inline-label] :as args}]
  [base/checkbox
   (merge (apply dissoc args known-keys)
          {:toggle toggle
           :checked (or value false)
           :label inline-label
           :on-change #(rf/dispatch [::set-field db-path key (aget %2 "checked")])})])

(defmethod input :toggle [args]
  (checkbox (assoc args :toggle true)))

(defmethod input :checkbox [args]
  (checkbox args))

(defmethod input :radio [{current-value :value :keys [db-path key options] :as args}]
  [:div
   (for [{:keys [value text]} options]
     [base/form-radio
      (merge (apply dissoc args known-keys)
             {:label text
              :value value
              :checked (= value current-value)
              :on-change #(rf/dispatch [::set-field db-path key (aget %2 "value")])})])])

(defmethod input :i18n [{:keys [value db-path key culture]}]
  [i18n-input/input
   {:entity value
    :culture culture
    :on-change #(rf/dispatch [::set-field db-path key %])}])

(defmethod input :i18n-textarea [{:keys [value db-path key culture]}]
  [i18n-input/input
   {:entity value
    :culture culture
    :control :textarea
    :on-change #(rf/dispatch [::set-field db-path key %])}])

(def state-key ::state)

(defmethod input :entity [{:keys [value db-path key options on-search-change]
                           {:keys [in out] :or {in identity out identity}} :xform}]
  [base/dropdown {:placeholder (i18n ::search)
                  :selection true
                  :default-value (if value (-> value in pr-str) "")
                  :icon "search"
                  :search (fn [options _] options)
                  :options (map (fn [{:keys [text value]}]
                                  {:text text
                                   :value (pr-str value)})
                                options)
                  :on-change #(rf/dispatch [::set-field db-path key (-> (.-value %2)
                                                                        reader/read-string
                                                                        out)])
                  :on-search-change on-search-change}])

(defmethod input :combobox [{:keys [value db-path key options on-change-fx] :as args}]
  [base/dropdown
   (merge (apply dissoc args known-keys)
          {:fluid true
           :selection true
           :default-value (if value (pr-str value) "")
           :on-change #(let [new-value (reader/read-string (.-value %2))]
                         (rf/dispatch [::set-field db-path key new-value])
                         (when on-change-fx
                           (rf/dispatch (conj on-change-fx new-value))))
           :options (map (fn [{:keys [text value]}]
                           {:text text
                            :value (pr-str value)})
                         options)})])

(defmethod input :tags [{:keys [value db-path key options forbid-additions]
                         {:keys [in out] :or {in identity out identity}} :xform}]
  [base/dropdown
   {:allowAdditions (not forbid-additions)
    :multiple true
    :fluid true
    :search true
    :selection true
    :options (map (fn [{:keys [text value]}]
                    {:text text
                     :value (pr-str value)})
                  options)
    :default-value (->> value
                        (in)
                        (map pr-str)
                        (set))
    :on-change (fn [_ result]
                 (rf/dispatch [::set-field db-path key
                               (->> (.-value result)
                                    (map reader/read-string)
                                    (out)
                                    (set))]))}])

(defmethod input :amount [{:keys [value db-path key]}]
  [amount-input/input
   {:amount value
    :on-change-fx [::set-field db-path key]}])

(defn- parse-value [type value]
  (cond
    (= type :number) (js/parseInt value 10)
    :else value))

(defn- base-input [html-control {:keys [value db-path key type inline-label on-change-fx] :as args}]
  (let [infractions @(rf/subscribe [::field.infractions db-path key])]
    [base/input
     {:label inline-label
      :icon true}
     [html-control (merge (apply dissoc args known-keys)
                          {:default-value (or value "")
                           :type (or type :text)
                           :on-change #(let [new-value (parse-value type (-> % .-target .-value))]
                                         (rf/dispatch [::set-field db-path key new-value])
                                         (when on-change-fx
                                           (rf/dispatch (conj on-change-fx new-value))))})]
     (when (seq infractions)
       [base/popup
        {:content (->> infractions
                       (map #(apply i18n %))
                       (str/join "\n"))
         :trigger (reagent/as-element
                   [base/icon {:class "link"
                               :name "warning sign"}])}])]))

(defmethod input :textarea [data]
  (base-input :textarea data))

(defmethod input :default [data]
  (base-input :input data))

(defn field [{:keys [db-path key label inline-label width] :as args}]
  (let [infractions @(rf/subscribe [::field.infractions db-path key])]
    [base/form-field
     {:width width
      :error (and (some? infractions) (seq infractions))}
     (when-not inline-label
       [:label label])
     [input
      (merge args
             {:value (get-in @(rf/subscribe [::data db-path]) (if (sequential? key)
                                                                key
                                                                [key]))})]]))