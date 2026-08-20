package dev.klipper.configurator.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.klipper.configurator.core.ConfigCatalog
import dev.klipper.configurator.core.ConfigGenerator
import dev.klipper.configurator.core.ConfigProject
import dev.klipper.configurator.core.DeploymentTarget
import dev.klipper.configurator.core.DriverKind
import dev.klipper.configurator.core.Kinematics
import dev.klipper.configurator.core.ProbeKind
import dev.klipper.configurator.core.ProjectCodec

class ConfiguratorActivity : Activity() {
    private lateinit var name: EditText
    private lateinit var target: Spinner
    private lateinit var mechanics: Spinner
    private lateinit var kinematics: Spinner
    private lateinit var bedWidth: EditText
    private lateinit var bedDepth: EditText
    private lateinit var buildHeight: EditText
    private lateinit var zMotors: Spinner
    private lateinit var controller: Spinner
    private lateinit var serial: EditText
    private lateinit var xyDriver: Spinner
    private lateinit var zDriver: Spinner
    private lateinit var extruderDriver: Spinner
    private lateinit var probe: Spinner
    private lateinit var bedMesh: CheckBox
    private lateinit var adaptiveMesh: CheckBox
    private lateinit var inputShaper: CheckBox
    private lateinit var filamentSensor: CheckBox
    private var advanced = ConfigProject()
    private var pendingExport: ByteArray? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(buildContent())
        val saved = getSharedPreferences("configurator", MODE_PRIVATE).getString("draft", null)
            ?.let { runCatching { ProjectCodec.decode(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull() }
        populate(saved ?: ConfigProject())
    }

    override fun onPause() {
        super.onPause()
        val bytes = ProjectCodec.encode(readProject())
        getSharedPreferences("configurator", MODE_PRIVATE).edit()
            .putString("draft", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).apply()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> runCatching {
                contentResolver.openOutputStream(uri, "w")!!.use { it.write(requireNotNull(pendingExport)) }
            }.onSuccess { toast("Configuration bundle exported") }.onFailure { showError("Export failed", it) }
            REQUEST_IMPORT -> runCatching {
                contentResolver.openInputStream(uri)!!.use { ConfigGenerator.importProject(it.readBytes()) }
            }.onSuccess { populate(it); toast("Editable project imported") }.onFailure { showError("Import failed", it) }
        }
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BACKGROUND) }
        root.addView(TextView(this).apply {
            text = "Klipper Configurator"; textSize = 22f; setTextColor(Color.WHITE); setPadding(dp(18), dp(18), dp(18), dp(8))
        })
        root.addView(TextView(this).apply {
            text = "Build an offline, editable Klipper config bundle. Generated values are starting points—verify every electrical and safety setting on the printer."
            textSize = 13f; setTextColor(MUTED); setPadding(dp(18), 0, dp(18), dp(12))
        })
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(24)) }
        name = edit(form, "Project and printer name")
        section(form, "1 · Target and mechanics")
        target = spinner(form, "Deployment target", DeploymentTarget.entries.map { it.label })
        mechanics = spinner(form, "Physical printer profile", ConfigCatalog.mechanics.map { "${it.name} · ${it.status}" })
        form.addView(action("Apply selected profile defaults") { applyMechanicsDefaults() })
        kinematics = spinner(form, "Kinematics", Kinematics.entries.map { it.label })
        val dims = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bedWidth = numeric(dims, "Width"); bedDepth = numeric(dims, "Depth"); buildHeight = numeric(dims, "Height")
        form.addView(dims)
        zMotors = spinner(form, "Z-axis motors", listOf("1 · single", "2 · dual", "3 · kinematic three-point", "4 · quad gantry"))
        section(form, "2 · Controller and drivers")
        controller = spinner(form, "Controller and exact revision", ConfigCatalog.controllers.map { "${it.name} ${it.exactRevision}" })
        serial = edit(form, "MCU serial / bridge path")
        xyDriver = spinner(form, "X/Y motor drivers", DriverKind.entries.map { it.label })
        zDriver = spinner(form, "Z motor driver", DriverKind.entries.map { it.label })
        extruderDriver = spinner(form, "Extruder motor driver", DriverKind.entries.map { it.label })
        section(form, "3 · Probe and leveling")
        probe = spinner(form, "Probe", ProbeKind.entries.map { it.label })
        bedMesh = check(form, "Bed mesh")
        adaptiveMesh = check(form, "Native Klipper adaptive mesh")
        section(form, "4 · Common features")
        inputShaper = check(form, "Input shaper placeholders")
        filamentSensor = check(form, "Filament runout sensor")
        form.addView(action("Advanced motion, thermal, probe, and pin settings") { advancedEditor() })
        val preview = action("Preview and validate") { preview() }
        val export = action("Export ZIP bundle") { export() }
        val import = action("Import editable ZIP") { import() }
        form.addView(preview); form.addView(export); form.addView(import)
        ConfiguratorHostRegistry.host?.let { host ->
            form.addView(action("Apply safely to ${host.label}") { confirmApply(host) })
            form.addView(action("Roll back last local apply") { confirmRollback(host) })
        }
        root.addView(ScrollView(this).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun readProject(): ConfigProject {
        val selectedTarget = DeploymentTarget.entries[target.selectedItemPosition]
        return ConfigProject(
            name = name.text.toString().trim().ifBlank { "My Klipper Printer" }, target = selectedTarget,
            mechanicsProfile = ConfigCatalog.mechanics[mechanics.selectedItemPosition].id,
            kinematics = Kinematics.entries[kinematics.selectedItemPosition],
            bedWidth = bedWidth.text.toString().toIntOrNull() ?: 220, bedDepth = bedDepth.text.toString().toIntOrNull() ?: 220,
            buildHeight = buildHeight.text.toString().toIntOrNull() ?: 250, zMotorCount = zMotors.selectedItemPosition + 1,
            controllerId = ConfigCatalog.controllers[controller.selectedItemPosition].id, mcuSerial = serial.text.toString().trim(),
            xyDriver = DriverKind.entries[xyDriver.selectedItemPosition], zDriver = DriverKind.entries[zDriver.selectedItemPosition],
            extruderDriver = DriverKind.entries[extruderDriver.selectedItemPosition], probe = ProbeKind.entries[probe.selectedItemPosition],
            bedMesh = bedMesh.isChecked, adaptiveMesh = adaptiveMesh.isChecked, inputShaper = inputShaper.isChecked,
            filamentSensor = filamentSensor.isChecked, maxVelocity = advanced.maxVelocity, maxAccel = advanced.maxAccel,
            xyRotationDistance = advanced.xyRotationDistance, zRotationDistance = advanced.zRotationDistance,
            extruderRotationDistance = advanced.extruderRotationDistance, xyRunCurrent = advanced.xyRunCurrent,
            zRunCurrent = advanced.zRunCurrent, extruderRunCurrent = advanced.extruderRunCurrent,
            hotendSensor = advanced.hotendSensor, bedSensor = advanced.bedSensor, hotendMaxTemp = advanced.hotendMaxTemp,
            bedMaxTemp = advanced.bedMaxTemp, probeXOffset = advanced.probeXOffset, probeYOffset = advanced.probeYOffset,
            probeZOffset = advanced.probeZOffset, pinOverrides = advanced.pinOverrides,
        )
    }

    private fun populate(p: ConfigProject) {
        advanced = p
        name.setText(p.name); target.setSelection(p.target.ordinal)
        mechanics.setSelection(ConfigCatalog.mechanics.indexOfFirst { it.id == p.mechanicsProfile }.coerceAtLeast(0))
        kinematics.setSelection(p.kinematics.ordinal); bedWidth.setText(p.bedWidth.toString()); bedDepth.setText(p.bedDepth.toString())
        buildHeight.setText(p.buildHeight.toString()); zMotors.setSelection((p.zMotorCount - 1).coerceIn(0, 3))
        controller.setSelection(ConfigCatalog.controllers.indexOfFirst { it.id == p.controllerId }.coerceAtLeast(0)); serial.setText(p.mcuSerial)
        xyDriver.setSelection(p.xyDriver.ordinal); zDriver.setSelection(p.zDriver.ordinal); extruderDriver.setSelection(p.extruderDriver.ordinal)
        probe.setSelection(p.probe.ordinal); bedMesh.isChecked = p.bedMesh; adaptiveMesh.isChecked = p.adaptiveMesh
        inputShaper.isChecked = p.inputShaper; filamentSensor.isChecked = p.filamentSensor
    }

    private fun applyMechanicsDefaults() {
        val item = ConfigCatalog.mechanics[mechanics.selectedItemPosition]
        bedWidth.setText(item.width.toString()); bedDepth.setText(item.depth.toString()); buildHeight.setText(item.height.toString())
        kinematics.setSelection(item.kinematics.ordinal); zMotors.setSelection((item.zMotors - 1).coerceIn(0, 3))
        toast("Applied ${item.name} defaults; review them before export")
    }

    private fun advancedEditor() {
        val p = readProject()
        val editor = EditText(this).apply {
            setText(buildString {
                appendLine("max_velocity=${p.maxVelocity}"); appendLine("max_accel=${p.maxAccel}")
                appendLine("xy_rotation_distance=${p.xyRotationDistance}"); appendLine("z_rotation_distance=${p.zRotationDistance}")
                appendLine("extruder_rotation_distance=${p.extruderRotationDistance}"); appendLine("xy_run_current=${p.xyRunCurrent}")
                appendLine("z_run_current=${p.zRunCurrent}"); appendLine("extruder_run_current=${p.extruderRunCurrent}")
                appendLine("hotend_sensor=${p.hotendSensor}"); appendLine("bed_sensor=${p.bedSensor}")
                appendLine("hotend_max_temp=${p.hotendMaxTemp}"); appendLine("bed_max_temp=${p.bedMaxTemp}")
                appendLine("probe_x_offset=${p.probeXOffset}"); appendLine("probe_y_offset=${p.probeYOffset}"); appendLine("probe_z_offset=${p.probeZOffset}")
                appendLine("# Pin overrides use pin.<logical_name>=VALUE")
                p.pinOverrides.toSortedMap().forEach { (key, value) -> appendLine("pin.$key=$value") }
            }); setTextColor(Color.WHITE); setBackgroundColor(PANEL); setPadding(dp(12), dp(12), dp(12), dp(12)); minLines = 18
        }
        AlertDialog.Builder(this).setTitle("Advanced settings").setView(ScrollView(this).apply { addView(editor) })
            .setPositiveButton("Save") { _, _ -> runCatching { saveAdvanced(editor.text.toString()) }.onFailure { showError("Invalid advanced settings", it) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveAdvanced(text: String) {
        val values = text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.associate { line ->
            val at = line.indexOf('='); require(at > 0) { "Expected key=value: $line" }; line.substring(0, at).trim() to line.substring(at + 1).trim()
        }
        fun number(key: String, fallback: Double) = values[key]?.toDoubleOrNull() ?: fallback
        fun integer(key: String, fallback: Int) = values[key]?.toIntOrNull() ?: fallback
        advanced = advanced.copy(maxVelocity = integer("max_velocity", advanced.maxVelocity), maxAccel = integer("max_accel", advanced.maxAccel),
            xyRotationDistance = number("xy_rotation_distance", advanced.xyRotationDistance), zRotationDistance = number("z_rotation_distance", advanced.zRotationDistance),
            extruderRotationDistance = number("extruder_rotation_distance", advanced.extruderRotationDistance), xyRunCurrent = number("xy_run_current", advanced.xyRunCurrent),
            zRunCurrent = number("z_run_current", advanced.zRunCurrent), extruderRunCurrent = number("extruder_run_current", advanced.extruderRunCurrent),
            hotendSensor = values["hotend_sensor"] ?: advanced.hotendSensor, bedSensor = values["bed_sensor"] ?: advanced.bedSensor,
            hotendMaxTemp = integer("hotend_max_temp", advanced.hotendMaxTemp), bedMaxTemp = integer("bed_max_temp", advanced.bedMaxTemp),
            probeXOffset = number("probe_x_offset", advanced.probeXOffset), probeYOffset = number("probe_y_offset", advanced.probeYOffset),
            probeZOffset = number("probe_z_offset", advanced.probeZOffset), pinOverrides = values.filterKeys { it.startsWith("pin.") }.mapKeys { it.key.removePrefix("pin.") })
        toast("Advanced settings saved")
    }

    private fun confirmApply(host: ConfiguratorHost) = AlertDialog.Builder(this).setTitle("Apply generated config?")
        .setMessage("This is allowed only for the safe starter config or a previously configurator-managed config. A backup is created first. Klipper may restart.")
        .setPositiveButton("Apply") { _, _ -> toast(host.applyBundle(ConfigGenerator.zip(ConfigGenerator.generate(readProject())))) }.setNegativeButton("Cancel", null).show()

    private fun confirmRollback(host: ConfiguratorHost) = AlertDialog.Builder(this).setTitle("Restore previous config?")
        .setMessage("Restore the most recent configurator backup and restart Klipper if it is running?")
        .setPositiveButton("Restore") { _, _ -> toast(host.rollback()) }.setNegativeButton("Cancel", null).show()

    private fun preview() {
        val bundle = ConfigGenerator.generate(readProject())
        val report = bundle.files.getValue("validation-report.txt").decodeToString()
        val config = bundle.files.getValue("printer.cfg").decodeToString()
        AlertDialog.Builder(this).setTitle("Validation and root config").setMessage(report + "\n" + config)
            .setPositiveButton("Export ZIP") { _, _ -> export() }.setNegativeButton("Close", null).show()
    }

    @Suppress("DEPRECATION")
    private fun export() {
        pendingExport = ConfigGenerator.zip(ConfigGenerator.generate(readProject()))
        val safe = readProject().name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "klipper-config" }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_TITLE, "$safe-klipper-config.zip") }, REQUEST_EXPORT)
    }

    @Suppress("DEPRECATION")
    private fun import() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip" }, REQUEST_IMPORT)
    private fun section(parent: LinearLayout, text: String) = parent.addView(TextView(this).apply { this.text = text; textSize = 17f; setTextColor(Color.WHITE); setPadding(dp(4), dp(18), dp(4), dp(8)) })
    private fun label(parent: LinearLayout, text: String) = parent.addView(TextView(this).apply { this.text = text; textSize = 12f; setTextColor(MUTED); setPadding(dp(4), dp(8), dp(4), dp(3)) })
    private fun edit(parent: LinearLayout, hint: String): EditText { label(parent, hint); return EditText(this).apply { setTextColor(Color.WHITE); setHintTextColor(MUTED); setSingleLine(); setBackgroundColor(PANEL); setPadding(dp(12), dp(10), dp(12), dp(10)); parent.addView(this, LinearLayout.LayoutParams(-1, -2)) } }
    private fun numeric(parent: LinearLayout, hint: String): EditText = EditText(this).apply { this.hint = hint; inputType = 2; setTextColor(Color.WHITE); setHintTextColor(MUTED); setBackgroundColor(PANEL); setPadding(dp(10), dp(10), dp(10), dp(10)); parent.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
    private fun spinner(parent: LinearLayout, title: String, values: List<String>): Spinner { label(parent, title); return Spinner(this).apply { adapter = ArrayAdapter(this@ConfiguratorActivity, android.R.layout.simple_spinner_dropdown_item, values); setBackgroundColor(PANEL); parent.addView(this, LinearLayout.LayoutParams(-1, dp(48))) } }
    private fun check(parent: LinearLayout, text: String): CheckBox = CheckBox(this).apply { this.text = text; setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(PRIMARY); parent.addView(this) }
    private fun action(text: String, click: () -> Unit) = Button(this).apply { this.text = text; setTextColor(Color.WHITE); setBackgroundColor(PRIMARY); isAllCaps = false; gravity = Gravity.CENTER; setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(8), 0, 0) } }
    private fun showError(title: String, error: Throwable) = AlertDialog.Builder(this).setTitle(title).setMessage(error.message ?: error.javaClass.simpleName).setPositiveButton("OK", null).show()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_EXPORT = 41; private const val REQUEST_IMPORT = 42; private val BACKGROUND = Color.rgb(14, 16, 19); private val PANEL = Color.rgb(33, 37, 42); private val MUTED = Color.rgb(182, 182, 182); private val PRIMARY = Color.rgb(33, 150, 243) }
}
