# Read-only export of the legacy SQL Server database to CSV files (UTF-8).
# Usage: powershell -File export-from-sqlserver.ps1 [-Server ".\SQLEXPRESS"] [-Database RAJARAJESHWARI]
param(
    [string]$Server = ".\SQLEXPRESS",
    [string]$Database = "RAJARAJESHWARI",
    [string]$OutDir = "$PSScriptRoot\data"
)
New-Item -ItemType Directory -Force $OutDir | Out-Null
$cs = "Server=$Server;Database=$Database;Integrated Security=True"
$conn = New-Object System.Data.SqlClient.SqlConnection $cs
$conn.Open()

function Export-Query($name, $sql) {
    $cmd = $conn.CreateCommand(); $cmd.CommandText = $sql; $cmd.CommandTimeout = 600
    $r = $cmd.ExecuteReader()
    $sw = New-Object System.IO.StreamWriter("$OutDir\$name.csv", $false, (New-Object System.Text.UTF8Encoding($false)))
    $names = 0..($r.FieldCount-1) | ForEach-Object { $r.GetName($_) }
    $sw.WriteLine(($names -join ","))
    $n = 0
    while ($r.Read()) {
        $vals = for ($i=0; $i -lt $r.FieldCount; $i++) {
            if ($r.IsDBNull($i)) { "" }
            else {
                $v = $r.GetValue($i)
                if ($v -is [datetime]) { $v = $v.ToString("yyyy-MM-dd") }
                elseif ($v -is [bool]) { $v = $(if ($v) { "t" } else { "f" }) }
                else { $v = [string]$v }
                '"' + $v.Replace('"','""') + '"'
            }
        }
        $sw.WriteLine(($vals -join ","))
        $n++
    }
    $sw.Close(); $r.Close()
    Write-Host "$name : $n rows"
}

Export-Query "roles"    "SELECT RoleID, RoleDesc, IsActive, Reason FROM Roles_Master WITH (TABLOCK)"
Export-Query "users"    "SELECT UserID, UserName, Password, FirstName, LastName, EmailId, RoleID FROM Users WITH (TABLOCK)"
Export-Query "note_types" "SELECT CAST(NOTEID AS int) NOTEID, NOTE FROM DROPDOWN_MASTER WITH (TABLOCK) WHERE NOTE IS NOT NULL AND NOTE<>'NULL' AND NOTEID IS NOT NULL AND NOTEID<>'NULL'"
Export-Query "debtors"  "SELECT DEBTORID, DEBTORCODE, DEBTORNAME, DATEOFJION, GENDER, EMAIL_ADDR, DEBTORPHONE, GUARANTORNAME, GUARANTORPHONE, DEBTORHNO, DEBTORSTREET, DEBTORSEQ, DEBTORCITY, DEBTORSTATE, DEBTORPIN, GUARANTORHNO, GUARANTORSTREET, GUARANTORCITY, GUARANTORSTATE, GUARANTORPIN, Photo, UserID, ISACTIVE, DeletedBy, DeleteReason FROM DEBTORS_MASTER WITH (TABLOCK) ORDER BY DEBTORID"
Export-Query "deposits" "SELECT DEPOSITEID, DEBTORCODE, ACCOUNTNO, AMOUNTOFADVANCE, NOOFDAYS, DAYOFPAYMENT, DATEOFADVANCE, CLOSINGDATE, NOTE, ACCOUNTENDDATE, ACCOUNTSTATUS, UserID, ISACTIVE, DeletedBy, DeleteReason FROM DAYWEISE_DEPOSITES WITH (TABLOCK) ORDER BY DEPOSITEID"
Export-Query "payments" "SELECT DAYWISEPAYMENTID, DEBTORCODE, ACCOUNTNO, DAYDATE, AMOUNT, UserID, ISACTIVE, DeletedBy, DeleteReason FROM DAYWISE_PAYMENTS WITH (TABLOCK) ORDER BY DAYWISEPAYMENTID"
$conn.Close()

