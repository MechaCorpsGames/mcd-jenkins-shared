#!/usr/bin/env python3
import os, sys, re
root = os.environ["MCD_FAKE_DOCKER_ROOT"]
def rows():
    out = []
    for line in open(os.path.join(root, "containers.tsv")):
        line = line.rstrip("\n")
        if line:
            cid, name = line.split("\t")
            out.append({"id": cid, "name": name})
    return out
def save(rs):
    with open(os.path.join(root, "containers.tsv"), "w") as fh:
        for r in rs:
            fh.write("%s\t%s\n" % (r["id"], r["name"]))
def note(f, v):
    with open(os.path.join(root, f), "a") as fh:
        fh.write(v + "\n")
    # One ordered log as well, because per-verb files lose the interleaving and
    # the ORDER of rename/update/kill is the property under test.
    with open(os.path.join(root, "ops.txt"), "a") as fh:
        fh.write("%s %s\n" % (f.split(".")[0], v))
def find(cid):
    for r in rows():
        if r["id"] == cid:
            return r
    sys.exit(1)
a = sys.argv[1:]
if a[:1] == ["ps"]:
    # Only the filtered form the helper uses is modelled; anything else is a test
    # bug and must be loud rather than answered with a plausible guess.
    if "--filter" not in a or "-q" not in a:
        sys.stderr.write("fake docker: unmodelled ps %r\n" % (a,)); sys.exit(2)
    f = a[a.index("--filter") + 1]
    m = re.match(r"^name=\^/\?(.*)$", f)
    if not m:
        sys.stderr.write("fake docker: unmodelled filter %r\n" % (f,)); sys.exit(2)
    pat = m.group(1)
    for r in rows():
        if pat.endswith("$"):
            if r["name"] == pat[:-1]:
                print(r["id"])
        elif r["name"].startswith(pat):
            print(r["id"])
elif a[:1] == ["inspect"]:
    fmt = a[a.index("--format") + 1]
    r = find(a[-1])
    if fmt != "{{.Name}}":
        sys.stderr.write("fake docker: unmodelled inspect %r\n" % (fmt,)); sys.exit(2)
    print("/" + r["name"])
elif a[:1] == ["rename"]:
    rs = rows()
    for r in rs:
        if r["id"] == a[1]:
            note("renamed.txt", "%s -> %s" % (r["name"], a[2]))
            r["name"] = a[2]
            save(rs); sys.exit(0)
    sys.exit(1)
elif a[:1] == ["update"]:
    note("updated.txt", " ".join(a[1:]))
elif a[:2] == ["kill", "-s"]:
    note("signalled.txt", "%s %s" % (a[2], find(a[3])["name"]))
elif a[:1] == ["stop"]:
    note("stopped.txt", find(a[1])["name"])
elif a[:1] == ["rm"]:
    if "-f" in a:
        note("removed.txt", "FORCED " + find(a[-1])["name"])
    else:
        note("removed.txt", find(a[-1])["name"])
else:
    sys.stderr.write("fake docker: unmodelled command %r\n" % (a,)); sys.exit(2)
