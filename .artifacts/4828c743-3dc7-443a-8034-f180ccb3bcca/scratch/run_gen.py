import os

# Read the complete kt from kt_complete.txt
with open('C:/Users/halva/AndroidStudioProjects/Camara/.artifacts/4828c743-3dc7-443a-8034-f180ccb3bcca/scratch/kt_complete.txt') as f:
    kt = f.read()

# Write MainActivity.kt
target = 'C:/Users/halva/AndroidStudioProjects/Camara/app/src/main/java/com/example/camara/MainActivity.kt'
with open(target, 'w', encoding='utf-8') as f:
    f.write(kt)
print('MainActivity.kt written with', kt.count(chr(10)), 'lines')

# Write write_main.py
script_path = 'C:/Users/halva/AndroidStudioProjects/Camara/.artifacts/4828c743-3dc7-443a-8034-f180ccb3bcca/scratch/write_main.py'
script = 'import os\n\ncontent = """' + kt + '"""\n\ntarget = "C:/Users/halva/AndroidStudioProjects/Camara/app/src/main/java/com/example/camara/MainActivity.kt"\nwith open(target, "w", encoding="utf-8") as f:\n    f.write(content)\nprint("MainActivity.kt written with", content.count(chr(10)), "lines")\n'
with open(script_path, 'w', encoding='utf-8') as f:
    f.write(script)
print('Script written with', script.count(chr(10)), 'lines')