{config, pkgs, ...}:
with pkgs.lib;
{
  options =
    let keyType = types.submodule ({ config, name, ...}:{
        options.name = mkOption {
          type = types.str;
          default = name;
          description = "Key name - resulting key will be /run/keys/<name>";
        };

        options.keyCommand = mkOption {
          default = null;
          example = [ "pass" "show" "secrettoken" ];
          type = types.nullOr (types.listOf types.str);
          description = "Command to use to get the secret, when deploying";
        };
        
        options.keyFile = mkOption {
          default = null;
          type = types.nullOr types.path;
          apply = value: if value == null then null else toString value;
        };

        options.user = mkOption {
          default = "root";
          type = types.str;
          description = ''
            The user which will be the owner of the key file.
          '';
        };

        options.group = mkOption {
          default = "root";
          type = types.str;
          description = ''
            The group that will be set for the key file.
          '';
        };

        options.permissions = mkOption {
          default = "0600";
          example = "0640";
          type = types.str;
          description = ''
            The default permissions to set for the key file, needs to be in the
            format accepted by ``chmod(1)``.
          '';
        };
    });
    in
    {
      deployment.keys = mkOption {
        type = types.attrsOf keyType;
        default = {};
      };
    };
}
