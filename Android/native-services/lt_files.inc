/* ---------- shared files: the guest's /var/mobile/Media over AFC, many operations on one connection ---------- */

struct file_info {
	int is_dir, is_link;
	uint64_t size;
	int64_t mtime_ms;
};

/* A path inside the guest's media folder: absolute, no "..", no empty parts. */
static int files_remote_valid(const char *path)
{
	size_t n = path ? strlen(path) : 0;
	if (n == 0 || n > 1000 || path[0] != '/' || strstr(path, "//") || strstr(path, "/../"))
		return 0;
	return !(n >= 3 && strcmp(path + n - 3, "/..") == 0);
}

static int files_local_valid(const char *path)
{
	size_t n = path ? strlen(path) : 0;
	return n > 1 && n < 1000 && path[0] == '/';
}

/* 0 = read, 1 = absent, -1 = failed (an error line has been written). */
static int files_info(afc_client_t afc, const char *path, struct file_info *out)
{
	char **info = NULL;
	afc_error_t err = afc_get_file_info(afc, path, &info);
	if (err == AFC_E_OBJECT_NOT_FOUND)
		return 1;
	if (afc_check(err, "read iOS file information"))
		return -1;
	memset(out, 0, sizeof(*out));
	for (size_t i = 0; info && info[i] && info[i + 1]; i += 2) {
		if (!strcmp(info[i], "st_ifmt")) {
			out->is_dir = !strcmp(info[i + 1], "S_IFDIR");
			out->is_link = !strcmp(info[i + 1], "S_IFLNK");
		} else if (!strcmp(info[i], "st_size")) {
			out->size = strtoull(info[i + 1], NULL, 10);
		} else if (!strcmp(info[i], "st_mtime")) {
			out->mtime_ms = (int64_t)(strtoull(info[i + 1], NULL, 10) / 1000000ULL);
		}
	}
	if (info)
		afc_dictionary_free(info);
	return 0;
}

static int files_list(afc_client_t afc, const char *dir)
{
	if (!files_remote_valid(dir)) {
		emit_error("That iOS folder name is not allowed.");
		return 1;
	}
	char **names = NULL;
	afc_error_t err = afc_read_directory(afc, dir, &names);
	struct buf b = {0};
	if (err == AFC_E_OBJECT_NOT_FOUND) {
		buf_puts(&b, "{\"result\":{\"exists\":false,\"entries\":[]}}");
		emit(&b);
		return 0;
	}
	if (afc_check(err, "read the iOS folder"))
		return 1;
	buf_puts(&b, "{\"result\":{\"exists\":true,\"entries\":[");
	unsigned count = 0;
	for (size_t i = 0; names && names[i]; i++) {
		if (!strcmp(names[i], ".") || !strcmp(names[i], ".."))
			continue;
		char path[1100];
		snprintf(path, sizeof(path), "%s%s%s", dir, strcmp(dir, "/") ? "/" : "", names[i]);
		struct file_info fi;
		int rc = files_info(afc, path, &fi);
		if (rc < 0)
			goto fail;
		if (rc == 1 || fi.is_link)
			continue;
		if (++count > 5000) {
			emit_error("The iOS folder has more than 5000 items.");
			goto fail;
		}
		if (count > 1)
			buf_puts(&b, ",");
		buf_puts(&b, "{\"name\":");
		json_string(&b, names[i]);
		buf_printf(&b, ",\"dir\":%s,\"bytes\":%" PRIu64 ",\"mtime\":%" PRId64 "}", fi.is_dir ? "true" : "false", fi.size, fi.mtime_ms);
	}
	buf_puts(&b, "]}}");
	emit(&b);
	if (names)
		afc_dictionary_free(names);
	return 0;
fail:
	free(b.data);
	if (names)
		afc_dictionary_free(names);
	return 1;
}

static int files_mkdir(afc_client_t afc, const char *path)
{
	if (!files_remote_valid(path)) {
		emit_error("That iOS folder name is not allowed.");
		return 1;
	}
	/* An existing folder is fine. */
	afc_make_directory(afc, path);
	struct file_info fi;
	int rc = files_info(afc, path, &fi);
	if (rc < 0)
		return 1;
	if (rc == 1 || !fi.is_dir) {
		emit_error("Could not make the iOS folder %s.", path);
		return 1;
	}
	struct buf b = {0};
	buf_puts(&b, "{\"result\":{\"ok\":true}}");
	emit(&b);
	return 0;
}

static int files_remove(afc_client_t afc, const char *path)
{
	if (!files_remote_valid(path) || !strcmp(path, "/")) {
		emit_error("That iOS file name is not allowed.");
		return 1;
	}
	afc_error_t err = afc_remove_path(afc, path);
	if (err != AFC_E_SUCCESS && err != AFC_E_OBJECT_NOT_FOUND) {
		afc_check(err, "remove the iOS file");
		return 1;
	}
	struct buf b = {0};
	buf_puts(&b, "{\"result\":{\"ok\":true}}");
	emit(&b);
	return 0;
}

static int files_pull(afc_client_t afc, const char *remote, const char *local)
{
	if (!files_remote_valid(remote) || !files_local_valid(local)) {
		emit_error("That file name is not allowed.");
		return 1;
	}
	struct file_info fi;
	int rc = files_info(afc, remote, &fi);
	if (rc < 0)
		return 1;
	if (rc == 1 || fi.is_dir) {
		emit_error("%s is not a file on the iOS device.", remote);
		return 1;
	}
	uint64_t handle = 0;
	if (afc_check(afc_file_open(afc, remote, AFC_FOPEN_RDONLY, &handle), "open the iOS file"))
		return 1;
	char part[1100];
	snprintf(part, sizeof(part), "%s.part", local);
	int fd = open(part, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
	if (fd < 0) {
		emit_error("Could not write %s: %s", part, strerror(errno));
		afc_file_close(afc, handle);
		return 1;
	}
	static char chunk[65536];
	uint64_t total = 0;
	for (;;) {
		uint32_t got = 0;
		afc_error_t err = afc_file_read(afc, handle, chunk, sizeof(chunk), &got);
		if (err != AFC_E_SUCCESS) {
			afc_check(err, "read the iOS file");
			goto fail;
		}
		if (got == 0)
			break;
		for (uint32_t off = 0; off < got;) {
			ssize_t n = write(fd, chunk + off, got - off);
			if (n < 0 && errno == EINTR)
				continue;
			if (n <= 0) {
				emit_error("Could not write %s: %s", part, strerror(errno));
				goto fail;
			}
			off += (uint32_t)n;
		}
		total += got;
	}
	afc_file_close(afc, handle);
	if (close(fd) != 0 || rename(part, local) != 0) {
		emit_error("Could not save %s: %s", local, strerror(errno));
		unlink(part);
		return 1;
	}
	struct buf b = {0};
	buf_printf(&b, "{\"result\":{\"bytes\":%" PRIu64 "}}", total);
	emit(&b);
	return 0;
fail:
	afc_file_close(afc, handle);
	close(fd);
	unlink(part);
	return 1;
}

static int files_push(afc_client_t afc, const char *local, const char *remote)
{
	if (!files_remote_valid(remote) || !files_local_valid(local)) {
		emit_error("That file name is not allowed.");
		return 1;
	}
	int fd = open(local, O_RDONLY | O_CLOEXEC);
	if (fd < 0) {
		emit_error("Could not read %s: %s", local, strerror(errno));
		return 1;
	}
	uint64_t handle = 0;
	if (afc_check(afc_file_open(afc, remote, AFC_FOPEN_WRONLY, &handle), "create the iOS file")) {
		close(fd);
		return 1;
	}
	static char chunk[65536];
	uint64_t total = 0;
	for (;;) {
		ssize_t got = read(fd, chunk, sizeof(chunk));
		if (got < 0 && errno == EINTR)
			continue;
		if (got < 0) {
			emit_error("Could not read %s: %s", local, strerror(errno));
			goto fail;
		}
		if (got == 0)
			break;
		for (uint32_t off = 0; off < (uint32_t)got;) {
			uint32_t written = 0;
			if (afc_check(afc_file_write(afc, handle, chunk + off, (uint32_t)got - off, &written), "write the iOS file"))
				goto fail_quiet;
			if (written == 0) {
				emit_error("The iOS device stopped accepting data.");
				goto fail_quiet;
			}
			off += written;
		}
		total += (uint64_t)got;
	}
	close(fd);
	if (afc_check(afc_file_close(afc, handle), "close the iOS file"))
		return 1;
	struct buf b = {0};
	buf_printf(&b, "{\"result\":{\"bytes\":%" PRIu64 "}}", total);
	emit(&b);
	return 0;
fail:
fail_quiet:
	afc_file_close(afc, handle);
	close(fd);
	return 1;
}

static int files_fields(char *line, char **fields, int max)
{
	int n = 0;
	char *p = line;
	while (n < max) {
		fields[n++] = p;
		char *tab = strchr(p, '\t');
		if (!tab)
			break;
		*tab = 0;
		p = tab + 1;
	}
	return n;
}

/* One request per line on stdin, tab-separated; one result or error line answers each:
 *   LIST dir | MKDIR dir | RM path | PULL remote local | PUSH local remote
 * Ends when stdin closes. */
static int action_files_session(struct session *s)
{
	afc_client_t afc = deb_afc_open(s);
	if (!afc)
		return 1;
	/* The guest's own id, so the app keeps one folder per device. Empty when it will not say. */
	char *udid = NULL;
	{
		plist_t node = NULL;
		if (s->lockdown && lockdownd_get_value(s->lockdown, NULL, "UniqueDeviceID", &node) == LOCKDOWN_E_SUCCESS && node &&
		    plist_get_node_type(node) == PLIST_STRING)
			plist_get_string_val(node, &udid);
		if (node)
			plist_free(node);
	}
	struct buf ready = {0};
	buf_puts(&ready, "{\"result\":{\"ready\":true,\"udid\":");
	json_string(&ready, udid ? udid : "");
	buf_puts(&ready, "}}");
	emit(&ready);
	free(udid);
	char *line = NULL;
	size_t cap = 0;
	ssize_t len;
	while ((len = getline(&line, &cap, stdin)) > 0) {
		while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r'))
			line[--len] = 0;
		if (len == 0)
			continue;
		char *f[3] = {NULL, NULL, NULL};
		int n = files_fields(line, f, 3);
		if (!strcmp(f[0], "LIST") && n == 2)
			files_list(afc, f[1]);
		else if (!strcmp(f[0], "MKDIR") && n == 2)
			files_mkdir(afc, f[1]);
		else if (!strcmp(f[0], "RM") && n == 2)
			files_remove(afc, f[1]);
		else if (!strcmp(f[0], "PULL") && n == 3)
			files_pull(afc, f[1], f[2]);
		else if (!strcmp(f[0], "PUSH") && n == 3)
			files_push(afc, f[1], f[2]);
		else
			emit_error("Unknown file request.");
	}
	free(line);
	afc_client_free(afc);
	return 0;
}

static int files_session_main(int argc, char **argv)
{
	signal(SIGPIPE, SIG_IGN);
	const char *socket_path = NULL;
	for (int i = 2; i < argc; i++) {
		if (!strcmp(argv[i], "--socket") && i + 1 < argc && !socket_path) {
			socket_path = argv[++i];
		} else {
			usage_error("files-session accepts --socket PATH only.");
			return 1;
		}
	}
	if (!socket_path) {
		usage_error("--socket is required.");
		return 1;
	}
	if (check_socket(socket_path) != 0)
		return 1;
	char address[sizeof(((struct sockaddr_un *)0)->sun_path) + 8];
	snprintf(address, sizeof(address), "UNIX:%s", socket_path);
	if (setenv("USBMUXD_SOCKET_ADDRESS", address, 1) != 0) {
		emit_error("Could not select the guest usbmux socket.");
		return 1;
	}
	struct session s = {0};
	if (session_open(&s) != 0)
		return 1;
	int rc = action_files_session(&s);
	session_close(&s);
	return rc;
}
