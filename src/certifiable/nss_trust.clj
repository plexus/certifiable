(ns certifiable.nss-trust
  (:require [clojure.java.io :as io]
            [certifiable.util :as util]
            [certifiable.log :as log]
            [clojure.java.shell :as shell :refer [sh]])
  (:import [java.io File]))

(defmulti firefox-profile-dir identity)

(defmethod firefox-profile-dir :default [_]
  nil)

(defmethod firefox-profile-dir :macos [_]
  (io/file (System/getProperty "user.home") "Library/Application Support/Firefox/Profiles"))

(defmethod firefox-profile-dir :linux [_]
  (io/file (System/getProperty "user.home") ".mozilla/firefox"))

(defmulti chromium-profile-dir identity)

(defmethod chromium-profile-dir :default [_]
  nil)

(defmethod chromium-profile-dir :linux [_]
  (io/file (System/getProperty "user.home") "snap/chromium"))

(defn firefox-profiles []
  (when-let [dir ^File (firefox-profile-dir (util/os?))]
    (and (.exists dir)
         (map io/file
              (filter
               #(.isDirectory ^File %)
               (.listFiles dir))))))

(defn nss-shared-db []
  (io/file (System/getProperty "user.home") ".pki/nssdb"))

(defn chromium-profiles []
  (when-let [dir ^File (chromium-profile-dir (util/os?))]
    (and (.exists dir)
         (->> (.listFiles dir)
              (map #(io/file % ".pki/nssdb"))
              (filter #(.isDirectory ^File %))))))

(defn prefix-profile-path [profile-path]
  (cond
    (.exists (io/file profile-path "cert9.db")) (str "sql:" profile-path)
    (.exists (io/file profile-path "cert8.db")) (str "dbm:" profile-path)
    :else nil))

(defn cert-db-paths []
  (keep prefix-profile-path (concat (firefox-profiles)
                                    (chromium-profiles)
                                    [(nss-shared-db)])))

(def certutil-path
  (memoize
   (fn []
     (cond
       (util/command-exists? "certutil") "certutil"
       (util/command-exists? "/usr/local/opt/nss/bin/certutil") "/usr/local/opt/nss/bin/certutil"
       :else (and (util/command-exists? "brew")
                  (when-let [nss-path (:out (sh "brew" "--prefix" "nss"))]
                    (let [certutil-path' (str (io/file nss-path "bin" "certutil"))]
                      (when (util/command-exists? certutil-path')
                        certutil-path'))))))))

(defn certutil-installed? [] (certutil-path))

(defn ca-uniq-name [pem-path]
  (let [cert (util/certificate pem-path)
        dname (subs (.getName (.getIssuerDN cert)) 3)]
    (str dname " " (.getSerialNumber cert))))

(def command-log (atom []))

(defn certutil-cmd [& args]
  (when (certutil-installed?)
    (let [res (apply sh (certutil-path) args)]
      (swap! command-log conj (assoc res :cmd args))
      res)))

(defn cert-valid-cmd [prefixed-profile-path uniq-name]
  (certutil-cmd "-V" "-d" prefixed-profile-path "-u" "L" "-n" uniq-name))

;; certutil -A -d "sql:/Users/bhauman/Library/Application Support/Firefox/Profiles/n94d69rw.default-1530010864728" -t C,, -n "Testing certifiable" -i ~/_certifiable_certs/localhost-1d070e4/dev-root-trust-this.pem
(defn cert-add-cmd [prefixed-profile-path uniq-name pem-path]
  (certutil-cmd "-A" "-d" prefixed-profile-path "-t" "C,," "-n" uniq-name "-i" (str pem-path)))

(defn cert-delete-cmd [prefixed-profile-path uniq-name]
  (certutil-cmd "-D" "-d" prefixed-profile-path "-n" uniq-name))

(defn has-cert? [pem-path]
  (let [uniq-name (ca-uniq-name pem-path)]
    (->> (cert-db-paths)
         (map #(cert-valid-cmd % uniq-name))
         (map :exit)
         (some zero?))))

(defn add-cert [pem-path]
  (let [uniq-name (ca-uniq-name pem-path)]
    (->> (cert-db-paths)
         (mapv #(and (cert-add-cmd % uniq-name pem-path) %))
         not-empty)))

(defn remove-cert [pem-path]
  (let [uniq-name (ca-uniq-name pem-path)]
    (->> (cert-db-paths)
         (mapv #(cert-delete-cmd % uniq-name)))))

(defn install-trust! [pem-path]
  (when (#{:macos :linux} (util/os?))
    (log/info "Attempting to add root certificate to browsers' nss trust store.")
    (if-not (certutil-path)
      (do
        (log/info "Warning \"certutil\" command is not available so this certificate will not be installed for Firefox")
        (log/info "Please install \"certutil\" with \"brew install nss\""))
      (let [uniq-name (pr-str (ca-uniq-name pem-path))]
        (if (has-cert? pem-path)
          (do (log/info "Cert" uniq-name "already present in browsers' trust store.")
              true)
          (if-let [profiles (add-cert pem-path)]
            (do
              (doseq [profile profiles]
                (log/info "Cert" uniq-name "successfully added to browser trust store " profile))
              true)
            (do
              (doseq [{:keys [cmd out err]} @command-log]
                (apply println "$" cmd)
                (println out)
                (println err))
              (log/info "FAILED adding" uniq-name "to Firefox trust store!")
              (log/info "Add the certifcate manually"))))))))

#_(def pem-path (io/file (System/getProperty "user.home") "_certifiable_certs/localhost-1d070e4/dev-root-trust-this.pem"))

#_(install-trust! pem-path)

#_(has-cert? pem-path)

#_(add-cert pem-path)

#_(remove-cert pem-path)

