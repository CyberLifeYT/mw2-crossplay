import subprocess

tids = [26386, 26387, 26388, 26389, 26390, 26391, 26392, 26393]
for tid in tids:
    res = subprocess.run(["adb", "-s", "GIGYA6ZXGYCAKNKJ", "shell", f"cat /proc/26301/task/{tid}/status"], capture_output=True, text=True)
    lines = [l for l in res.stdout.splitlines() if any(k in l for k in ["Name:", "State:", "voluntary_ctxt_switches:"])]
    print(f"=== TID {tid} ===")
    print("\n".join(lines))
