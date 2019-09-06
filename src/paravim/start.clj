(ns paravim.start
  (:require [paravim.core :as c]
            [paravim.repl :as repl]
            [libvim-clj.core :as v]
            [clojure.string :as str]
            [play-cljc.gl.core :as pc]
            [parinferish.core :as par]
            [clojure.core.async :as async])
  (:import  [org.lwjgl.glfw GLFW Callbacks
             GLFWCursorPosCallbackI GLFWKeyCallbackI GLFWCharCallbackI
             GLFWMouseButtonCallbackI GLFWWindowSizeCallbackI]
            [org.lwjgl.opengl GL GL41]
            [org.lwjgl.system MemoryUtil]
            [javax.sound.sampled AudioSystem Clip])
  (:gen-class))

(defn listen-for-resize [window game]
  (GLFW/glfwSetWindowSizeCallback window
    (reify GLFWWindowSizeCallbackI
      (invoke [this window width height]
        (swap! c/*state
               (fn [{:keys [current-buffer current-tab tab->buffer] :as state}]
                 (as-> state state
                       (if current-buffer
                         (c/update-cursor state game current-buffer)
                         state)
                       ;; if we're in the repl, make sure both the input and output are refreshed
                       (if-let [other-tab (case current-tab
                                            :repl-in :repl-out
                                            :repl-out :repl-in
                                            nil)]
                         (c/update-cursor state game (tab->buffer other-tab))
                         state))))))))

(defn open-buffer-for-tab! [vim {:keys [current-buffer current-tab tab->buffer] :as state}]
  (if-let [buffer-for-tab (tab->buffer current-tab)]
    (when (not= current-buffer buffer-for-tab)
      (v/set-current-buffer vim buffer-for-tab))
    (when-let [path (c/tab->path current-tab)]
      (v/open-buffer vim path))))

(defn get-density-ratio [window]
  (let [*fb-width (MemoryUtil/memAllocInt 1)
        *window-width (MemoryUtil/memAllocInt 1)
        _ (GLFW/glfwGetFramebufferSize window *fb-width nil)
        _ (GLFW/glfwGetWindowSize window *window-width nil)
        fb-width (.get *fb-width)
        window-width (.get *window-width)]
    (MemoryUtil/memFree *fb-width)
    (MemoryUtil/memFree *window-width)
    (float (/ fb-width window-width))))

(defn listen-for-mouse [window game vim]
  (GLFW/glfwSetCursorPosCallback window
    (reify GLFWCursorPosCallbackI
      (invoke [this window xpos ypos]
        (as-> (swap! c/*state
                (fn [state]
                  (let [density-ratio (get-density-ratio window)
                        x (* xpos density-ratio)
                        y (* ypos density-ratio)]
                    (c/update-mouse state game x y))))
              state
              (GLFW/glfwSetCursor window
                                  (GLFW/glfwCreateStandardCursor
                                    (case (:mouse-type state)
                                      ;:ibeam GLFW/GLFW_IBEAM_CURSOR
                                      :hand GLFW/GLFW_HAND_CURSOR
                                      GLFW/GLFW_ARROW_CURSOR)))))))
  (GLFW/glfwSetMouseButtonCallback window
    (reify GLFWMouseButtonCallbackI
      (invoke [this window button action mods]
        (when (and (= button GLFW/GLFW_MOUSE_BUTTON_LEFT)
                   (= action GLFW/GLFW_PRESS))
          (open-buffer-for-tab! vim (swap! c/*state c/click-mouse)))))))

(def keycode->keyword
  {GLFW/GLFW_KEY_BACKSPACE :backspace
   GLFW/GLFW_KEY_TAB :tab
   GLFW/GLFW_KEY_ENTER :enter
   GLFW/GLFW_KEY_ESCAPE :escape
   GLFW/GLFW_KEY_UP :up
   GLFW/GLFW_KEY_DOWN :down
   GLFW/GLFW_KEY_LEFT :left
   GLFW/GLFW_KEY_RIGHT :right})

(def keycode->char
  {GLFW/GLFW_KEY_D \D
   GLFW/GLFW_KEY_R \R
   GLFW/GLFW_KEY_U \U})

;; https://vim.fandom.com/wiki/Mapping_keys_in_Vim_-_Tutorial_%28Part_2%29

(def keyword->name
  {:backspace "<BS>"
   :tab "<Tab>"
   :enter "<Enter>"
   :escape "<Esc>"
   :up "<Up>"
   :down "<Down>"
   :left "<Left>"
   :right "<Right>"})

(defn repl-enter! [vim callback {:keys [out out-pipe]}]
  (let [buffer-ptr (v/get-current-buffer vim)
        lines (vec (for [i (range (v/get-line-count vim buffer-ptr))]
                (v/get-line vim buffer-ptr (inc i))))
        text (str (str/join \newline lines) \newline)]
    (run! callback ["g" "g" "d" "G"])
    (doto out
      (.write text)
      .flush)
    (doto out-pipe
      (.write text)
      .flush)))

(defn listen-for-keys [window callback vim pipes]
  (GLFW/glfwSetKeyCallback window
    (reify GLFWKeyCallbackI
      (invoke [this window keycode scancode action mods]
        (when (= action GLFW/GLFW_PRESS)
          (let [control? (not= 0 (bit-and mods GLFW/GLFW_MOD_CONTROL))
                alt? (not= 0 (bit-and mods GLFW/GLFW_MOD_ALT))
                shift? (not= 0 (bit-and mods GLFW/GLFW_MOD_SHIFT))]
            (if-let [k (keycode->keyword keycode)]
              (cond
                (and (or control? alt?) (= k :tab))
                (open-buffer-for-tab! vim (swap! c/*state c/change-tab (if shift? -1 1)))
                (and (= k :enter)
                     (= 'NORMAL (v/get-mode vim))
                     (= :repl-in (:current-tab @c/*state)))
                (repl-enter! vim callback pipes)
                :else
                (when-let [key-name (keyword->name k)]
                  (callback key-name)))
              (when control?
                (when-let [ch (keycode->char keycode)]
                  (callback (str "<C-" (when shift? "S-") ch ">")))))))))))

(defn listen-for-chars [window callback]
  (GLFW/glfwSetCharCallback window
    (reify GLFWCharCallbackI
      (invoke [this window codepoint]
        (callback (str (char codepoint)))))))

(defn update-buffers [state]
  (if-let [updates (not-empty (:buffer-updates state))]
    (let [buffer-ptrs (set (map :buffer-ptr updates))]
      (as-> state state
            (reduce
              (fn [state {:keys [buffer-ptr lines first-line line-count-change]}]
                (c/update-text state buffer-ptr lines first-line line-count-change))
              state
              updates)
            (assoc state :buffer-updates [])
            (reduce (fn [state buffer-ptr]
                      (if (:clojure? (c/get-buffer state buffer-ptr))
                        (-> state
                            (c/parse-clojure-buffer buffer-ptr false)
                            (c/update-clojure-buffer buffer-ptr))
                        state))
                    state buffer-ptrs)))
    state))

(defn apply-parinfer! [{:keys [mode] :as state} vim buffer-ptr]
  (let [{:keys [parsed-code needs-parinfer?]} (get-in state [:buffers buffer-ptr])]
    (when (and needs-parinfer?
               ('#{NORMAL INSERT} mode))
      (let [cursor-line (v/get-cursor-line vim)
            cursor-column (v/get-cursor-column vim)
            diffs (par/diff parsed-code)]
        (when (seq diffs)
          (when (= 'NORMAL mode)
            (v/input vim "i"))
          (doseq [{:keys [line column content action]} diffs]
            ;; move all the way to the first column
            (dotimes [_ (v/get-cursor-column vim)]
              (v/input vim "<Left>"))
            ;; move to the right line
            (let [line-diff (- line (dec (v/get-cursor-line vim)))]
              (cond
                (pos? line-diff)
                (dotimes [_ line-diff]
                  (v/input vim "<Down>"))
                (neg? line-diff)
                (dotimes [_ (* -1 line-diff)]
                  (v/input vim "<Up>"))))
            ;; move to the right column
            (dotimes [_ column]
              (v/input vim "<Right>"))
            ;; delete or insert characters
            (doseq [ch (seq content)]
              (case action
                :remove
                (v/input vim "<Del>")
                :insert
                (v/input vim (str ch)))))
          ;; go back to the original position
          (v/set-cursor-position vim cursor-line cursor-column)
          (when (= 'NORMAL mode)
            (v/input vim "<Esc>"))))
      (swap! c/*state
        (fn [state]
          (-> state
              (assoc-in [:buffers buffer-ptr :needs-parinfer?] false)
              (update-buffers)))))))

(defn on-input [game vim s]
  (let [{:keys [mode command-text command-text-entity current-buffer]} @c/*state]
    (when (and (= 'INSERT mode) (= s "<Esc>"))
      (-> (swap! c/*state update-buffers)
          (apply-parinfer! vim current-buffer)))
    (if (and (= 'COMMAND_LINE mode) command-text)
      (let [pos (v/get-command-position vim)]
        (case s
          "<Tab>"
          (when (= (count command-text) pos)
            (when-let [completion-text (v/get-command-completion vim)]
              (when-let [first-part (str/last-index-of command-text " ")]
                (dotimes [_ (- (count command-text) (inc first-part))]
                  (v/input vim "<BS>"))
                (doseq [ch completion-text]
                  (v/input vim (str ch))))))
          ("<Right>" "<Left>" "<Up>" "<Down>")
          nil
          (v/input vim s)))
      (v/input vim s))
    (let [current-buffer (v/get-current-buffer vim)
          old-mode mode
          mode (v/get-mode vim)
          cursor-line (dec (v/get-cursor-line vim))
          cursor-column (v/get-cursor-column vim)]
      (cond-> (swap! c/*state
                (fn [state]
                  (-> state
                      (assoc :mode mode)
                      (cond-> (and (not= old-mode 'COMMAND_LINE)
                                   (= mode 'COMMAND_LINE))
                              (assoc :command-start s))
                      (c/update-command (v/get-command-text vim) (v/get-command-position vim))
                      (as-> state
                            (if (c/get-buffer state current-buffer)
                              (-> state
                                  (update-in [:buffers current-buffer] assoc :cursor-line cursor-line :cursor-column cursor-column)
                                  update-buffers
                                  (c/update-cursor game current-buffer)
                                  (c/update-highlight current-buffer)
                                  (cond-> (v/visual-active? vim)
                                          (c/update-selection current-buffer (-> (v/get-visual-range vim)
                                                                                 (update :start-line dec)
                                                                                 (update :end-line dec)))))
                              state)))))
              (and (not= 'INSERT mode)
                   (not= s "u"))
              (apply-parinfer! vim current-buffer)))))

(defn on-buf-enter [game vim buffer-ptr]
  (let [cursor-line (dec (v/get-cursor-line vim))
        cursor-column (v/get-cursor-column vim)
        path (v/get-file-name vim buffer-ptr)
        lines (vec (for [i (range (v/get-line-count vim buffer-ptr))]
                (v/get-line vim buffer-ptr (inc i))))]
    (swap! c/*state
      (fn [{:keys [tab->buffer] :as state}]
        (as-> state state
              (if path
                (let [canon-path (-> path java.io.File. .getCanonicalPath)
                      current-tab (or (some
                                        (fn [[tab path]]
                                          (when (= canon-path (-> path java.io.File. .getCanonicalPath))
                                            tab))
                                        c/tab->path)
                                      :files)]
                  (-> state
                      (assoc :current-buffer buffer-ptr :current-tab current-tab)
                      (update :tab->buffer assoc current-tab buffer-ptr)))
                (-> state
                    (assoc :current-buffer nil)
                    (update :tab->buffer assoc :files nil)))
              (if (and path (nil? (c/get-buffer state buffer-ptr)))
                (as-> state state
                      (c/assoc-buffer state buffer-ptr path lines)
                      (if (:clojure? (c/get-buffer state buffer-ptr))
                        (-> state
                            (c/parse-clojure-buffer buffer-ptr true)
                            (c/update-clojure-buffer buffer-ptr))
                        state)
                      (update-in state [:buffers buffer-ptr] assoc :cursor-line cursor-line :cursor-column cursor-column))
                state)
              (if path
                (c/update-cursor state game buffer-ptr)
                state))))))

(def ^:dynamic *update-ui?* true)

(defn on-inputs [game vim current-buffer {:keys [buffer string]}]
  (let [cursor-line (v/get-cursor-line vim)
        cursor-column (v/get-cursor-column vim)
        line-count (v/get-line-count vim buffer)
        char-count (count (v/get-line vim buffer line-count))]
    (binding [*update-ui?* false]
      (v/set-current-buffer vim buffer)
      (v/set-cursor-position vim line-count (dec char-count))
      (v/input vim "a")
      (doseq [ch string]
        (on-input game vim (str ch)))
      (v/input vim "<Esc>")
      (v/set-current-buffer vim current-buffer)
      (v/set-cursor-position vim cursor-line cursor-column))))

(defn split-inputs [vim inputs]
  (when (and (seq inputs) (= 'NORMAL (v/get-mode vim)))
    (let [current-buffer (v/get-current-buffer vim)
          *inputs-to-run (volatile! [])
          *inputs-to-delay (volatile! [])]
      (doseq [input inputs]
        (if (= (:buffer input) current-buffer)
          (vswap! *inputs-to-delay conj input)
          (vswap! *inputs-to-run conj input)))
      (let [inputs-to-run @*inputs-to-run]
        (when (seq inputs-to-run)
          [inputs-to-run @*inputs-to-delay current-buffer])))))

(defn poll-input [game vim c]
  (async/go-loop [delayed-inputs []]
    (if-let [[inputs-to-run inputs-to-delay current-buffer] (split-inputs vim delayed-inputs)]
      (do
        (doseq [input inputs-to-run]
          (on-inputs game vim current-buffer input))
        (recur inputs-to-delay))
      (let [input (async/<! c)]
        (if (string? input)
          (do
            (on-input game vim input)
            (recur delayed-inputs))
          (recur (conj delayed-inputs input)))))))

(defn -main [& args]
  (when-not (GLFW/glfwInit)
    (throw (Exception. "Unable to initialize GLFW")))
  (GLFW/glfwWindowHint GLFW/GLFW_VISIBLE GLFW/GLFW_FALSE)
  (GLFW/glfwWindowHint GLFW/GLFW_RESIZABLE GLFW/GLFW_TRUE)
  (GLFW/glfwWindowHint GLFW/GLFW_CONTEXT_VERSION_MAJOR 4)
  (GLFW/glfwWindowHint GLFW/GLFW_CONTEXT_VERSION_MINOR 1)
  (GLFW/glfwWindowHint GLFW/GLFW_OPENGL_FORWARD_COMPAT GL41/GL_TRUE)
  (GLFW/glfwWindowHint GLFW/GLFW_OPENGL_PROFILE GLFW/GLFW_OPENGL_CORE_PROFILE)
  (GLFW/glfwWindowHint GLFW/GLFW_TRANSPARENT_FRAMEBUFFER GLFW/GLFW_TRUE)
  (if-let [window (GLFW/glfwCreateWindow 1024 768 "Paravim" 0 0)]
    (do
      (GLFW/glfwMakeContextCurrent window)
      (GLFW/glfwSwapInterval 1)
      (GLFW/glfwShowWindow window)
      (GL/createCapabilities)
      (let [initial-game (assoc (pc/->game window)
                                :delta-time 0
                                :total-time 0)
            vim (doto (v/->vim)
                  v/init
                  (v/execute "set hidden")
                  (v/execute "set noswapfile")
                  (v/execute "set nobackup")
                  (v/execute "set nowritebackup")
                  (v/execute "set tabstop=2")
                  (v/execute "set softtabstop=2")
                  (v/execute "set shiftwidth=2")
                  (v/execute "set expandtab")
                  (v/execute "filetype plugin indent on"))
            vim-chan (async/chan)
            send-input! (partial async/put! vim-chan)
            pipes (repl/create-pipes)]
        (listen-for-resize window initial-game)
        (listen-for-mouse window initial-game vim)
        (listen-for-keys window send-input! vim pipes)
        (listen-for-chars window send-input!)
        (v/set-on-quit vim (fn [buffer-ptr force?]
                             (System/exit 0)))
        (v/set-on-auto-command vim (fn [buffer-ptr event]
                                     (case event
                                       EVENT_BUFENTER
                                       (when *update-ui?*
                                         (on-buf-enter initial-game vim buffer-ptr))
                                       nil)))
        (v/set-on-buffer-update vim (fn [buffer-ptr start-line end-line line-count-change]
                                      (let [first-line (dec start-line)
                                            last-line (+ (dec end-line) line-count-change)
                                            lines (vec (for [i (range first-line last-line)]
                                                         (v/get-line vim buffer-ptr (inc i))))]
                                        (swap! c/*state update :buffer-updates conj {:buffer-ptr buffer-ptr
                                                                                     :lines lines
                                                                                     :first-line first-line
                                                                                     :line-count-change line-count-change}))))
        (c/init initial-game)
        (run! #(v/open-buffer vim (c/tab->path %))
          [:repl-in :repl-out :files])
        (poll-input initial-game vim vim-chan)
        (when-let [buffer (-> @c/*state :tab->buffer :repl-out)]
          (repl/start-repl-thread! nil pipes #(async/put! vim-chan {:buffer buffer :string %})))
        (loop [game initial-game]
          (when-not (GLFW/glfwWindowShouldClose window)
            (let [ts (GLFW/glfwGetTime)
                  game (assoc game
                              :delta-time (- ts (:total-time game))
                              :total-time ts)
                  game (c/tick game)]
              (GLFW/glfwSwapBuffers window)
              (GLFW/glfwPollEvents)
              (recur game)))))
      (Callbacks/glfwFreeCallbacks window)
      (GLFW/glfwDestroyWindow window)
      (GLFW/glfwTerminate))
    (throw (Exception. "Failed to create window"))))

