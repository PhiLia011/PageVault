param(
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) "desktop\PageVault\Assets"
}
Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function New-RoundedRectanglePath([float]$x, [float]$y, [float]$width, [float]$height, [float]$radius) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $diameter = $radius * 2
    $path.AddArc($x, $y, $diameter, $diameter, 180, 90)
    $path.AddArc($x + $width - $diameter, $y, $diameter, $diameter, 270, 90)
    $path.AddArc($x + $width - $diameter, $y + $height - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($x, $y + $height - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-IconPng([int]$size) {
    $bitmap = New-Object System.Drawing.Bitmap($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)

    $scale = $size / 256.0
    $green = [System.Drawing.ColorTranslator]::FromHtml("#1E5B4F")
    $paper = [System.Drawing.ColorTranslator]::FromHtml("#F6F4EE")
    $fold = [System.Drawing.ColorTranslator]::FromHtml("#D8E7E1")
    $accent = [System.Drawing.ColorTranslator]::FromHtml("#E8743B")
    $ink = [System.Drawing.ColorTranslator]::FromHtml("#1E5B4F")

    $background = New-RoundedRectanglePath (12 * $scale) (12 * $scale) (232 * $scale) (232 * $scale) (54 * $scale)
    $graphics.FillPath((New-Object System.Drawing.SolidBrush($green)), $background)

    $page = New-Object System.Drawing.Drawing2D.GraphicsPath
    $page.AddPolygon([System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(70 * $scale, 47 * $scale),
        [System.Drawing.PointF]::new(164 * $scale, 47 * $scale),
        [System.Drawing.PointF]::new(194 * $scale, 77 * $scale),
        [System.Drawing.PointF]::new(194 * $scale, 204 * $scale),
        [System.Drawing.PointF]::new(70 * $scale, 204 * $scale)
    ))
    $graphics.FillPath((New-Object System.Drawing.SolidBrush($paper)), $page)
    $foldPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $foldPath.AddPolygon([System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(164 * $scale, 47 * $scale),
        [System.Drawing.PointF]::new(164 * $scale, 77 * $scale),
        [System.Drawing.PointF]::new(194 * $scale, 77 * $scale)
    ))
    $graphics.FillPath((New-Object System.Drawing.SolidBrush($fold)), $foldPath)

    $linePen = New-Object System.Drawing.Pen($ink, [Math]::Max(4 * $scale, 1))
    $linePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $linePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $graphics.DrawLine($linePen, 92 * $scale, 101 * $scale, 171 * $scale, 101 * $scale)
    $graphics.DrawLine($linePen, 92 * $scale, 124 * $scale, 171 * $scale, 124 * $scale)
    $graphics.DrawLine($linePen, 92 * $scale, 147 * $scale, 140 * $scale, 147 * $scale)

    $accentPath = New-RoundedRectanglePath (151 * $scale) (153 * $scale) (55 * $scale) (55 * $scale) (9 * $scale)
    $graphics.FillPath((New-Object System.Drawing.SolidBrush($accent)), $accentPath)

    $stream = New-Object System.IO.MemoryStream
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    $bytes = $stream.ToArray()
    $stream.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
    return ,$bytes
}

$sizes = @(16, 24, 32, 48, 64, 128, 256)
function New-IconDibFromPng([byte[]]$pngBytes) {
    $input = New-Object System.IO.MemoryStream(,$pngBytes)
    $source = New-Object System.Drawing.Bitmap($input)
    $width = $source.Width
    $height = $source.Height
    $rect = New-Object System.Drawing.Rectangle(0, 0, $width, $height)
    $locked = $source.LockBits(
        $rect,
        [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)

    $maskStride = [Math]::Floor(($width + 31) / 32) * 4
    $maskBytes = $maskStride * $height
    $pixelBytes = $width * $height * 4
    $output = New-Object System.IO.MemoryStream
    $writer = New-Object System.IO.BinaryWriter($output)

    $writer.Write([UInt32]40)
    $writer.Write([Int32]$width)
    $writer.Write([Int32]($height * 2))
    $writer.Write([UInt16]1)
    $writer.Write([UInt16]32)
    $writer.Write([UInt32]0)
    $writer.Write([UInt32]($pixelBytes + $maskBytes))
    $writer.Write([Int32]0)
    $writer.Write([Int32]0)
    $writer.Write([UInt32]0)
    $writer.Write([UInt32]0)

    $row = New-Object byte[] ($width * 4)
    for ($y = $height - 1; $y -ge 0; $y--) {
        [System.Runtime.InteropServices.Marshal]::Copy(
            [IntPtr]::Add($locked.Scan0, $y * $locked.Stride),
            $row, 0, $row.Length)
        $writer.Write($row)
    }
    $writer.Write((New-Object byte[] $maskBytes))

    $result = $output.ToArray()
    $writer.Dispose()
    $output.Dispose()
    $source.UnlockBits($locked)
    $source.Dispose()
    $input.Dispose()
    return ,$result
}

$images = foreach ($size in $sizes) {
    $png = New-IconPng $size
    $dib = New-IconDibFromPng $png
    [pscustomobject]@{
        Size = $size
        Bytes = $dib
    }
}

$icoPath = Join-Path $OutputDirectory "PageVault.ico"
$file = [System.IO.File]::Create($icoPath)
$writer = New-Object System.IO.BinaryWriter($file)
$writer.Write([UInt16]0)
$writer.Write([UInt16]1)
$writer.Write([UInt16]$images.Count)
$offset = 6 + (16 * $images.Count)
foreach ($image in $images) {
    $dimension = if ($image.Size -ge 256) { 0 } else { $image.Size }
    $writer.Write([byte]$dimension)
    $writer.Write([byte]$dimension)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([UInt16]1)
    $writer.Write([UInt16]32)
    $writer.Write([UInt32]$image.Bytes.Length)
    $writer.Write([UInt32]$offset)
    $offset += $image.Bytes.Length
}
foreach ($image in $images) {
    $writer.Write($image.Bytes)
}
$writer.Dispose()
$file.Dispose()

$pngPath = Join-Path $OutputDirectory "PageVault-256.png"
$preview = New-IconPng 256
[System.IO.File]::WriteAllBytes($pngPath, $preview)
$png512Path = Join-Path $OutputDirectory "PageVault-512.png"
$preview512 = New-IconPng 512
[System.IO.File]::WriteAllBytes($png512Path, $preview512)
Write-Host "Icon: $icoPath"
Write-Host "Preview: $pngPath"
Write-Host "Store icon: $png512Path"
