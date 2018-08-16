(ns file.system
  (:refer-clojure :exclude [exists?]))

(defonce fs (js/require "fs"))
(defonce path (js/require "path"))
(defonce process (js/require "process"))

(defonce watches (atom {}))

(defn- win? []
  (= "win32" (.-platform process)))

(defn- mac? []
  (= "darwin" (.-platform process)))

(defn- trailing-slash [s]
  (if (= "/" (subs s (- (count s) 1))) s (str s "/")))

(def add-trailing-slash
  (memoize trailing-slash))

(defn exists? [file]
  (.existsSync fs file))

(defn dir? [s]
  (and (exists? s) (.. fs (lstatSync s) isDirectory)))

(defn symlink? [s]
  (.. fs (lstatSync s) isSymbolicLink))

(defn realpath [file]
  (if (exists? file)
    (.realpathSync fs file)
    file))

(defn basename [file]
  (.basename path file))

(defn dirname [file]
  (.dirname path file))

(defn cwd []
  (.cwd js/process))

(defn slurp [file]
  (str (.readFileSync fs file)))

(defn head
  "Return the first n bytes of the given file (default 1024 bytes)."
  ([file] (head file 1024))
  ([file bytes]
   (when (exists? file)
     (let [buffer (.alloc js/Buffer bytes)
           descriptor (.openSync fs file "r")]
       (.readSync fs descriptor buffer 0 bytes 0)
       (.closeSync fs descriptor)
       (str buffer)))))

(defn rm [file]
  (.unlinkSync fs file))

(defn rmdir
  "Remove the given directory if it is empty."
  [dir]
  (.rmdirSync fs dir))

(defn dir-tree
  "Return a seq of all directories within and including the given dir"
  [dir]
  (if (dir? dir)
    (let [xfn (comp (map #(str (trailing-slash dir) %)) (filter dir?))
          dirs (into [] xfn (.readdirSync fs dir))]
      (reduce (fn [acc dir]
                (into acc (dir-tree dir))) [dir] dirs))
    []))

(defn file-tree
  "Return a seq of all files within the given dir"
  [dir]
  (if (dir? dir)
    (let [not-dir? (complement dir?)
          dirs (dir-tree dir)]
      (reduce (fn [acc dir]
                (let [file-xfn (comp (map #(str (trailing-slash dir) %)) (filter not-dir?))
                      files (into [] file-xfn (.readdirSync fs dir))]
                  (into acc files))) [] dirs))
    []))

(defn watched? [dir-or-file]
  (get @watches (realpath dir-or-file)))

(defn watch
  ([dir-or-file callback] (watch dir-or-file #js {} callback))
  ([dir-or-file options callback]
   (let [dir-or-file (realpath dir-or-file)]
     (if-let [watch (watched? dir-or-file)]
       watch
       (let [prefix (trailing-slash (if (dir? dir-or-file)
                                      dir-or-file
                                      (dirname dir-or-file)))
             callback-wrapper (fn [event-type filename]
                                (when filename
                                  (callback (str prefix filename))))]
         (let [watch (.watch fs dir-or-file options callback-wrapper)]
           (swap! watches assoc dir-or-file watch)
           watch))))))

(defn- recursive-watch-supported? []
  (or (mac?) (win?)))

(defn- watch-recursive-manually [dir callback]
  (let [wrapped-callback (fn [file]
                           (when (and (dir? file) (not (contains? @watches file)))
                             (watch-recursive-manually file callback))
                           (callback file))
        watches (doall (map #(watch % wrapped-callback) (dir-tree dir)))]
    ;; return composite facade to fs.FSWatcher providing a close method
    #js {:close #(run! (fn [watch] (.close watch)) watches)}))

(defn watch-recursive [dir callback]
  (if (not (dir? dir))
    (watch dir callback)
    (if (recursive-watch-supported?)
      (watch dir #js {:recursive true} callback)
      (watch-recursive-manually dir callback))))

(defn stop-watching [dir]
  (try
    (swap! watches (fn [watches]
                     (if-let [watch (get watches dir)]
                       (do (.close watch)
                           (dissoc watches dir))
                       watches)))
    (catch :default e)))

(defn stop-all-watches []
  (run! stop-watching (keys @watches)))

(declare rmdir-recursive)

(defn rm-contents
  "Remove everything inside the given directory, but keep the directory itself."
  [dir]
  (when (not (dir? dir))
    (throw (ex-info (str "Error: \"" dir "\" is not a directory") {:dir dir})))
  (let [items (map #(str (trailing-slash dir) %) (.readdirSync fs dir))]
    (run! rm (filter (complement dir?) items))
    (run! rmdir-recursive (filter dir? items))))

(defn rmdir-recursive
  "Remove the given directory and it's contents."
  [dir]
  (rm-contents dir)
  (rmdir dir))
